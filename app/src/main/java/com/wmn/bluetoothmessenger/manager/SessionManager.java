package com.wmn.bluetoothmessenger.manager;

import android.util.Log;

import com.wmn.bluetoothmessenger.util.Constants;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages the session lifecycle with a 30-minute inactivity timeout.
 * Inactivity is defined as: no message exchange and no user join.
 * When the timeout is reached, the session is terminated and all users are
 * disconnected.
 */
public class SessionManager {

    private static final String TAG = "SessionManager";

    private final AtomicLong lastActivityTime = new AtomicLong(System.currentTimeMillis());
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private SessionListener listener;
    private boolean running = false;

    public interface SessionListener {
        void onSessionTimeout();

        void onSessionWarning(long remainingMs);
    }

    public void setListener(SessionListener listener) {
        this.listener = listener;
    }

    /**
     * Start monitoring for session inactivity.
     */
    public void startMonitoring() {
        running = true;
        resetActivity();

        scheduler.scheduleAtFixedRate(() -> {
            if (!running)
                return;

            long elapsed = System.currentTimeMillis() - lastActivityTime.get();
            long remaining = Constants.SESSION_TIMEOUT_MS - elapsed;

            if (remaining <= 0) {
                // Session timed out!
                Log.d(TAG, "Session timeout reached - no activity for 30 minutes");
                running = false;
                if (listener != null) {
                    listener.onSessionTimeout();
                }
            } else if (remaining <= 5 * 60 * 1000) {
                // Warning: less than 5 minutes remaining
                if (listener != null) {
                    listener.onSessionWarning(remaining);
                }
            }
        }, Constants.SESSION_CHECK_INTERVAL_MS, Constants.SESSION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Reset the activity timer. Called on every message send/receive or user join.
     */
    public void resetActivity() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    /**
     * Get remaining time until session timeout.
     */
    public long getRemainingTimeMs() {
        long elapsed = System.currentTimeMillis() - lastActivityTime.get();
        return Math.max(0, Constants.SESSION_TIMEOUT_MS - elapsed);
    }

    /**
     * Check if the session is still active.
     */
    public boolean isActive() {
        return running && getRemainingTimeMs() > 0;
    }

    /**
     * Stop monitoring and shut down.
     */
    public void shutdown() {
        running = false;
        scheduler.shutdownNow();
    }
}

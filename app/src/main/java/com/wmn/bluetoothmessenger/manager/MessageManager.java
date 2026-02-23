package com.wmn.bluetoothmessenger.manager;

import com.wmn.bluetoothmessenger.model.ChatMessage;
import com.wmn.bluetoothmessenger.util.Constants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages in-memory message storage with Time-To-Live (TTL) support.
 * Messages automatically expire and are removed after 1 minute.
 * No persistent storage — all messages are ephemeral.
 */
public class MessageManager {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private MessageListener listener;

    public interface MessageListener {
        void onMessageAdded(ChatMessage message, int position);

        void onMessageRemoved(int position);

        void onMessagesChanged();
    }

    public void setListener(MessageListener listener) {
        this.listener = listener;
    }

    /**
     * Start the TTL cleanup scheduler.
     * Checks every 5 seconds for expired messages.
     */
    public void startTTLCleanup() {
        scheduler.scheduleAtFixedRate(() -> {
            synchronized (messages) {
                boolean changed = false;
                Iterator<ChatMessage> it = messages.iterator();
                int index = 0;
                while (it.hasNext()) {
                    ChatMessage msg = it.next();
                    if (msg.getType() == ChatMessage.TYPE_NORMAL && msg.isExpired()) {
                        it.remove();
                        if (listener != null) {
                            final int pos = index;
                            listener.onMessageRemoved(pos);
                        }
                        changed = true;
                    } else {
                        index++;
                    }
                }
                if (changed && listener != null) {
                    listener.onMessagesChanged();
                }
            }
        }, Constants.TTL_CHECK_INTERVAL_MS, Constants.TTL_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Add a new message to the in-memory store.
     */
    public void addMessage(ChatMessage message) {
        synchronized (messages) {
            messages.add(message);
            if (listener != null) {
                listener.onMessageAdded(message, messages.size() - 1);
            }
        }
    }

    /**
     * Get all current messages (snapshot).
     */
    public List<ChatMessage> getMessages() {
        synchronized (messages) {
            return new ArrayList<>(messages);
        }
    }

    /**
     * Get message count.
     */
    public int getMessageCount() {
        synchronized (messages) {
            return messages.size();
        }
    }

    /**
     * Clear all messages.
     */
    public void clearAll() {
        synchronized (messages) {
            messages.clear();
            if (listener != null) {
                listener.onMessagesChanged();
            }
        }
    }

    /**
     * Stop the TTL cleanup scheduler.
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}

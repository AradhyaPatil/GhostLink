package com.wmn.bluetoothmessenger.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import com.wmn.bluetoothmessenger.util.Constants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Core Bluetooth service that manages:
 * - Server socket (for hosts accepting connections)
 * - Client socket connections (for joining groups)
 * - All active ConnectedThread instances
 * - Broadcasting messages to all connected peers
 *
 * Uses Handler to relay events back to the UI thread.
 */
public class BluetoothService {

    private static final String TAG = "BluetoothService";

    // ── Singleton ──────────────────────────────────────────────────────────────
    private static volatile BluetoothService instance;

    /**
     * Create (or replace) the singleton instance. Call once from the Activity
     * that starts the Bluetooth session (CreateGroupActivity / JoinGroupActivity).
     */
    public static BluetoothService init(BluetoothAdapter adapter, Handler handler) {
        instance = new BluetoothService(adapter, handler);
        return instance;
    }

    /** Return the live singleton; null if not yet initialised. */
    public static BluetoothService getInstance() {
        return instance;
    }

    /** Swap the UI handler when moving between Activities. */
    public void setHandler(Handler newHandler) {
        this.handler = newHandler;
        // Also update every live ConnectedThread so in-flight messages reach the new handler
        synchronized (connectedThreads) {
            for (ConnectedThread thread : connectedThreads) {
                thread.setHandler(newHandler);
            }
        }
    }

    /**
     * Disconnect everything and clear the singleton reference.
     * Call when the user fully leaves (leaveGroup / SESSION_END).
     */
    public static void destroyInstance() {
        BluetoothService svc = instance;
        instance = null;
        if (svc != null) {
            svc.disconnect();
        }
    }
    // ──────────────────────────────────────────────────────────────────────────

    private final BluetoothAdapter adapter;
    private volatile Handler handler;

    private AcceptThread acceptThread;
    private final List<ConnectedThread> connectedThreads = Collections.synchronizedList(new ArrayList<>());

    private boolean isHost = false;
    private String passwordHash = "";

    // Callback interface for authentication on the host side
    public interface AuthCallback {
        boolean onAuthRequest(String receivedHash);

        void onAuthSuccess(String deviceName);

        void onAuthFail(String deviceName);
    }

    private AuthCallback authCallback;

    private BluetoothService(BluetoothAdapter adapter, Handler handler) {
        this.adapter = adapter;
        this.handler = handler;
    }

    public void setAuthCallback(AuthCallback callback) {
        this.authCallback = callback;
    }

    public void setPasswordHash(String hash) {
        this.passwordHash = hash;
    }

    // ========== HOST MODE ==========

    /**
     * Start as host: begins accepting incoming connections.
     */
    public void startHosting() {
        isHost = true;
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        acceptThread = new AcceptThread(adapter, handler, this);
        acceptThread.start();
        Log.d(TAG, "Started hosting, awaiting connections");
    }

    /**
     * Called by AcceptThread when a new connection is accepted.
     * Starts a ConnectedThread that will first handle authentication.
     */
    @SuppressWarnings("MissingPermission")
    public void onConnectionAccepted(BluetoothSocket socket) {
        String deviceName;
        try {
            deviceName = socket.getRemoteDevice().getName();
            if (deviceName == null)
                deviceName = socket.getRemoteDevice().getAddress();
        } catch (SecurityException e) {
            deviceName = socket.getRemoteDevice().getAddress();
        }

        Log.d(TAG, "New connection from: " + deviceName);

        // Create a temporary thread to handle auth
        final String finalDeviceName = deviceName;
        ConnectedThread thread = new ConnectedThread(socket, handler, deviceName);

        // Start a separate thread for the auth handshake on the host side
        new Thread(() -> {
            try {
                // Read the auth message from the client
                byte[] buffer = new byte[1024];
                int bytes = socket.getInputStream().read(buffer);
                String authMessage = new String(buffer, 0, bytes);

                if (authMessage.startsWith(Constants.PROTO_AUTH)) {
                    String clientHash = authMessage.substring(Constants.PROTO_AUTH.length());

                    if (authCallback != null && authCallback.onAuthRequest(clientHash)) {
                        // Auth successful
                        socket.getOutputStream().write(Constants.PROTO_AUTH_OK.getBytes());
                        socket.getOutputStream().flush();

                        // Register the connected thread
                        connectedThreads.add(thread);
                        thread.start();

                        handler.obtainMessage(Constants.MSG_CONNECTED, finalDeviceName).sendToTarget();

                        if (authCallback != null) {
                            authCallback.onAuthSuccess(finalDeviceName);
                        }

                        // Notify all existing members about the new member
                        broadcastMessage(Constants.PROTO_JOIN + finalDeviceName);
                    } else {
                        // Auth failed
                        socket.getOutputStream().write(Constants.PROTO_AUTH_FAIL.getBytes());
                        socket.getOutputStream().flush();

                        if (authCallback != null) {
                            authCallback.onAuthFail(finalDeviceName);
                        }

                        Thread.sleep(500);
                        socket.close();
                    }
                } else {
                    // Invalid protocol, close connection
                    socket.close();
                }
            } catch (IOException | InterruptedException e) {
                Log.e(TAG, "Auth handshake failed for " + finalDeviceName, e);
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }).start();
    }

    // ========== CLIENT MODE ==========

    /**
     * Connect to a host device as a client.
     */
    @SuppressWarnings("MissingPermission")
    public void connectToHost(BluetoothDevice device, String password) {
        isHost = false;

        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(Constants.BT_UUID);
                adapter.cancelDiscovery();
                socket.connect();

                // Send auth
                String hash = com.wmn.bluetoothmessenger.model.GroupInfo.hashPassword(password);
                socket.getOutputStream().write((Constants.PROTO_AUTH + hash).getBytes());
                socket.getOutputStream().flush();

                // Wait for auth response
                byte[] buffer = new byte[1024];
                int bytes = socket.getInputStream().read(buffer);
                String response = new String(buffer, 0, bytes);

                if (Constants.PROTO_AUTH_OK.equals(response)) {
                    String deviceName;
                    try {
                        deviceName = device.getName();
                        if (deviceName == null)
                            deviceName = device.getAddress();
                    } catch (SecurityException e) {
                        deviceName = device.getAddress();
                    }

                    ConnectedThread thread = new ConnectedThread(socket, handler, deviceName);
                    connectedThreads.add(thread);
                    thread.start();

                    handler.obtainMessage(Constants.MSG_CONNECTED, deviceName).sendToTarget();
                } else {
                    handler.obtainMessage(Constants.MSG_CONNECTION_FAILED, "Authentication failed").sendToTarget();
                    socket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Connection failed", e);
                handler.obtainMessage(Constants.MSG_CONNECTION_FAILED, e.getMessage()).sendToTarget();
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Bluetooth permission missing", e);
                handler.obtainMessage(Constants.MSG_CONNECTION_FAILED, "Permission denied").sendToTarget();
            }
        }).start();
    }

    // ========== MESSAGING ==========

    /**
     * Broadcast a message to ALL connected peers.
     */
    public void broadcastMessage(String message) {
        synchronized (connectedThreads) {
            List<ConnectedThread> deadThreads = new ArrayList<>();
            for (ConnectedThread thread : connectedThreads) {
                if (thread.isConnected()) {
                    thread.write(message);
                } else {
                    deadThreads.add(thread);
                }
            }
            // Clean up dead connections
            connectedThreads.removeAll(deadThreads);
        }
    }

    /**
     * Send a chat message to all peers (wraps with protocol prefix).
     */
    public void sendChatMessage(String senderName, String content) {
        String protocolMessage = Constants.PROTO_MSG + senderName + ":" + content;
        broadcastMessage(protocolMessage);
    }

    // ========== LIFECYCLE ==========

    /**
     * Get the count of active connections.
     */
    public int getConnectedCount() {
        synchronized (connectedThreads) {
            int count = 0;
            for (ConnectedThread thread : connectedThreads) {
                if (thread.isConnected())
                    count++;
            }
            return count;
        }
    }

    /**
     * Get the device names of all currently active connections.
     * Used by ChatActivity to seed the groupManager with pre-joined members.
     */
    public List<String> getConnectedDeviceNames() {
        synchronized (connectedThreads) {
            List<String> names = new ArrayList<>();
            for (ConnectedThread thread : connectedThreads) {
                if (thread.isConnected()) {
                    names.add(thread.getDeviceName());
                }
            }
            return names;
        }
    }

    public boolean isHost() {
        return isHost;
    }

    /**
     * Disconnect all connections and stop all threads.
     */
    public void disconnect() {
        // Send session end to all peers
        try {
            broadcastMessage(Constants.PROTO_SESSION_END);
        } catch (Exception ignored) {
        }

        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }

        synchronized (connectedThreads) {
            for (ConnectedThread thread : connectedThreads) {
                thread.cancel();
            }
            connectedThreads.clear();
        }

        Log.d(TAG, "All connections closed");
    }
}

package com.wmn.bluetoothmessenger.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Looper;
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
 * - Host migration (promoting a member when the host leaves)
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
    /**
     * Original Bluetooth adapter name, saved before hosting so we can restore it.
     */
    private String originalAdapterName;

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

    public String getPasswordHash() {
        return passwordHash;
    }

    // ========== HOST MODE ==========

    /**
     * Start as host: begins accepting incoming connections.
     * Changes the Bluetooth adapter name to "GhostLink_<groupName>" so that
     * scanning devices see the room name (device.getName() returns this).
     */
    @SuppressWarnings("MissingPermission")
    public void startHosting(String groupName) {
        isHost = true;

        // Change adapter name so scanners see the room name
        try {
            originalAdapterName = adapter.getName();
            adapter.setName(Constants.BT_SERVICE_PREFIX + groupName);
            Log.d(TAG, "Adapter name changed to: " + Constants.BT_SERVICE_PREFIX + groupName);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot change adapter name", e);
        }

        if (acceptThread != null) {
            acceptThread.cancel();
        }
        acceptThread = new AcceptThread(adapter, handler, this, groupName);
        acceptThread.start();
        Log.d(TAG, "Started hosting group: " + groupName + ", awaiting connections");
    }

    /**
     * Switch from client mode to host mode (used during host migration).
     * Starts AcceptThread and changes adapter name, but does NOT clear
     * existing connections (the old host connection is already dead).
     */
    @SuppressWarnings("MissingPermission")
    public void switchToHost(String roomName, String newPasswordHash) {
        isHost = true;
        this.passwordHash = newPasswordHash;

        // Change adapter name to advertise the room
        try {
            originalAdapterName = adapter.getName();
            // Strip prefix if we somehow already have it
            if (originalAdapterName != null && originalAdapterName.startsWith(Constants.BT_SERVICE_PREFIX)) {
                originalAdapterName = originalAdapterName.substring(Constants.BT_SERVICE_PREFIX.length());
            }
            adapter.setName(Constants.BT_SERVICE_PREFIX + roomName);
            Log.d(TAG, "Switched to host. Adapter name: " + Constants.BT_SERVICE_PREFIX + roomName);
        } catch (SecurityException e) {
            Log.e(TAG, "Cannot change adapter name during migration", e);
        }

        // Start accepting new connections
        if (acceptThread != null) {
            acceptThread.cancel();
        }
        acceptThread = new AcceptThread(adapter, handler, this, roomName);
        acceptThread.start();
        Log.d(TAG, "Host migration complete — now hosting: " + roomName);
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

        final String finalDeviceName = deviceName;
        ConnectedThread thread = new ConnectedThread(socket, handler, deviceName);

        // Auth handshake on a separate thread
        new Thread(() -> {
            try {
                byte[] buffer = new byte[1024];
                int bytes = socket.getInputStream().read(buffer);
                String authMessage = new String(buffer, 0, bytes);

                if (authMessage.startsWith(Constants.PROTO_AUTH)) {
                    String clientHash = authMessage.substring(Constants.PROTO_AUTH.length()).trim();

                    if (authCallback != null && authCallback.onAuthRequest(clientHash)) {
                        // Auth successful
                        broadcastMessage(Constants.PROTO_JOIN + finalDeviceName);

                        connectedThreads.add(thread);
                        thread.start();

                        socket.getOutputStream().write(Constants.PROTO_AUTH_OK.getBytes());
                        socket.getOutputStream().flush();

                        handler.obtainMessage(Constants.MSG_CONNECTED, finalDeviceName).sendToTarget();

                        if (authCallback != null) {
                            authCallback.onAuthSuccess(finalDeviceName);
                        }

                        // Tell the new client about existing members (Delay to avoid AUTH_OK handshake
                        // race condition)
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            synchronized (connectedThreads) {
                                for (ConnectedThread t : connectedThreads) {
                                    if (t != thread && t.isConnected()) {
                                        thread.write(Constants.PROTO_JOIN + t.getDeviceName());
                                    }
                                }
                            }
                        }, 500);

                    } else {
                        socket.getOutputStream().write(Constants.PROTO_AUTH_FAIL.getBytes());
                        socket.getOutputStream().flush();

                        if (authCallback != null) {
                            authCallback.onAuthFail(finalDeviceName);
                        }

                        Thread.sleep(500);
                        socket.close();
                    }
                } else {
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
     * Connect to a host device as a client (raw password version).
     */
    @SuppressWarnings("MissingPermission")
    public void connectToHost(BluetoothDevice device, String password) {
        String hash = com.wmn.bluetoothmessenger.model.GroupInfo.hashPassword(password);
        connectToHostWithHash(device, hash);
    }

    /**
     * Connect to a host device as a client using a pre-computed password hash.
     * Used during auto-reconnect after host migration.
     */
    @SuppressWarnings("MissingPermission")
    public void connectToHostWithHash(BluetoothDevice device, String hash) {
        isHost = false;

        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                socket = device.createRfcommSocketToServiceRecord(Constants.BT_UUID);
                adapter.cancelDiscovery();
                socket.connect();

                // Send auth with hash
                socket.getOutputStream().write((Constants.PROTO_AUTH + hash).getBytes());
                socket.getOutputStream().flush();

                // Wait for auth response
                byte[] buffer = new byte[1024];
                int bytes = socket.getInputStream().read(buffer);
                String response = new String(buffer, 0, bytes);

                if (response.startsWith(Constants.PROTO_AUTH_OK)) {
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
            connectedThreads.removeAll(deadThreads);
        }
    }

    /**
     * Send a message to ONE specific connected device by name.
     */
    public void sendToDevice(String deviceName, String message) {
        synchronized (connectedThreads) {
            for (ConnectedThread thread : connectedThreads) {
                if (thread.isConnected() && thread.getDeviceName().equals(deviceName)) {
                    thread.write(message);
                    return;
                }
            }
        }
        Log.w(TAG, "sendToDevice: no connected thread for " + deviceName);
    }

    /**
     * Send a chat message to all peers (wraps with protocol prefix).
     */
    public void sendChatMessage(String senderName, String content) {
        String protocolMessage = Constants.PROTO_MSG + senderName + ":" + content;
        broadcastMessage(protocolMessage);
    }

    // ========== HOST MIGRATION ==========

    /**
     * Initiate host migration before the host leaves.
     * Picks the first connected member, sends PROMOTE_HOST to them and
     * HOST_CHANGED to all other members.
     *
     * @param roomName     the current room name
     * @param passwordHash the current password hash
     * @param myName       the leaving host's device name
     * @return the promoted member's device name, or null if no members left
     */
    @SuppressWarnings("MissingPermission")
    public String initiateHostMigration(String roomName, String passwordHash, String myName) {
        synchronized (connectedThreads) {
            // Find the first live connected member
            ConnectedThread promotee = null;
            for (ConnectedThread thread : connectedThreads) {
                if (thread.isConnected()) {
                    promotee = thread;
                    break;
                }
            }

            if (promotee == null) {
                Log.d(TAG, "No members to promote — room will close");
                return null;
            }

            String promoteeName = promotee.getDeviceName();
            String promoteeAddress = "";
            try {
                // We need to get the Bluetooth address of the promoted member.
                // The address is stored in the socket's remote device.
                promoteeAddress = promotee.getRemoteAddress();
            } catch (Exception e) {
                Log.e(TAG, "Cannot get promotee address", e);
            }

            Log.d(TAG, "Promoting " + promoteeName + " (" + promoteeAddress + ") to host");

            // 1. Send PROMOTE_HOST to the chosen member
            String promoteMsg = Constants.PROTO_PROMOTE_HOST + roomName + ":" + passwordHash;
            promotee.write(promoteMsg);

            // 2. Send HOST_CHANGED to all OTHER members
            String changedMsg = Constants.PROTO_HOST_CHANGED + promoteeAddress;
            for (ConnectedThread thread : connectedThreads) {
                if (thread != promotee && thread.isConnected()) {
                    thread.write(changedMsg);
                }
            }

            // 3. Send LEAVE notification to all
            broadcastMessage(Constants.PROTO_LEAVE + myName);

            return promoteeName;
        }
    }

    // ========== LIFECYCLE ==========

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

    public String getOriginalAdapterName() {
        return originalAdapterName;
    }

    public BluetoothAdapter getAdapter() {
        return adapter;
    }

    /**
     * Disconnect all connections and stop all threads.
     * Only the HOST sends SESSION_END (not used during migration).
     */
    @SuppressWarnings("MissingPermission")
    public void disconnect() {
        if (isHost) {
            try {
                broadcastMessage(Constants.PROTO_SESSION_END);
            } catch (Exception ignored) {
            }
        }

        restoreAdapterName();
        stopAllThreads();
        Log.d(TAG, "All connections closed");
    }

    /**
     * Disconnect quietly — no SESSION_END broadcast. Used during host migration
     * so the promoted host doesn't interpret the disconnect as a session end.
     */
    @SuppressWarnings("MissingPermission")
    public void disconnectQuietly() {
        restoreAdapterName();
        stopAllThreads();
        Log.d(TAG, "Disconnected quietly (host migration)");
    }

    @SuppressWarnings("MissingPermission")
    private void restoreAdapterName() {
        if (originalAdapterName != null) {
            try {
                adapter.setName(originalAdapterName);
                Log.d(TAG, "Adapter name restored to: " + originalAdapterName);
            } catch (SecurityException ignored) {
            }
            originalAdapterName = null;
        }
    }

    private void stopAllThreads() {
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
    }
}

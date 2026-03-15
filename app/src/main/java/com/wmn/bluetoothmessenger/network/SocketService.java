package com.wmn.bluetoothmessenger.network;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wmn.bluetoothmessenger.util.Constants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import io.socket.client.IO;
import io.socket.client.Socket;

/**
 * Socket.IO client wrapper for online chat mode.
 * Mirrors BluetoothService's handler-based message delivery.
 */
public class SocketService {

    private static final String TAG = "SocketService";
    public static final String DEFAULT_SERVER_URL = "https://ghostlink-honq.onrender.com";

    private static SocketService instance;

    private Socket socket;
    private Handler handler;
    private String currentRoom;
    private String currentUsername;
    private final List<String> onlineUsers = new ArrayList<>();

    // Callback for file reception
    public interface FileReceivedCallback {
        void onFileReceived(String senderName, String fileName, String mimeType, String fileData);
    }

    private FileReceivedCallback fileCallback;

    private SocketService() {
    }

    public static synchronized SocketService getInstance() {
        if (instance == null) {
            instance = new SocketService();
        }
        return instance;
    }

    public static synchronized void destroyInstance() {
        if (instance != null) {
            instance.disconnect();
            instance = null;
        }
    }

    public void setHandler(Handler handler) {
        this.handler = handler;
    }

    public void setFileCallback(FileReceivedCallback callback) {
        this.fileCallback = callback;
    }

    /**
     * Connect to the Socket.IO server.
     */
    public void connect() {
        connect(DEFAULT_SERVER_URL);
    }

    /**
     * Connect to the Socket.IO server using an explicit URL.
     */
    public void connect(String serverUrl) {
        try {
            IO.Options opts = new IO.Options();
            opts.forceNew = true;
            opts.reconnection = true;
            opts.reconnectionAttempts = 10;
            opts.reconnectionDelay = 2000;
            opts.timeout = 10000;

            socket = IO.socket(URI.create(serverUrl), opts);
            setupListeners();
            socket.connect();
            Log.d(TAG, "Connecting to: " + serverUrl);
        } catch (Exception e) {
            Log.e(TAG, "Connection failed", e);
            if (handler != null) {
                handler.obtainMessage(Constants.MSG_CONNECTION_FAILED, "Connection failed: " + e.getMessage())
                        .sendToTarget();
            }
        }
    }

    /**
     * Join a room with credentials.
     */
    public void joinRoom(String roomName, String passwordHash, String username) {
        if (socket == null || !socket.connected()) {
            Log.e(TAG, "Not connected to server");
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("roomName", roomName);
            data.put("passwordHash", passwordHash);
            data.put("username", username);
            socket.emit("joinRoom", data);

            currentRoom = roomName;
            currentUsername = username;
        } catch (Exception e) {
            Log.e(TAG, "joinRoom failed", e);
        }
    }

    /**
     * Send an encrypted chat message.
     */
    public void sendMessage(String encryptedMessage) {
        if (socket == null || currentRoom == null)
            return;

        try {
            JSONObject data = new JSONObject();
            data.put("roomName", currentRoom);
            data.put("encryptedMessage", encryptedMessage);
            data.put("senderName", currentUsername);
            socket.emit("chatMessage", data);
        } catch (Exception e) {
            Log.e(TAG, "sendMessage failed", e);
        }
    }

    /**
     * Send an encrypted file.
     */
    public void sendFile(String fileName, String mimeType, String encryptedBase64Data) {
        if (socket == null || currentRoom == null)
            return;

        try {
            JSONObject data = new JSONObject();
            data.put("roomName", currentRoom);
            data.put("fileName", fileName);
            data.put("mimeType", mimeType);
            data.put("fileData", encryptedBase64Data);
            data.put("senderName", currentUsername);
            socket.emit("shareFile", data);
        } catch (Exception e) {
            Log.e(TAG, "sendFile failed", e);
        }
    }

    /**
     * Leave the current room.
     */
    public void leaveRoom() {
        if (socket != null) {
            socket.emit("leaveRoom");
        }
        currentRoom = null;
        currentUsername = null;
        onlineUsers.clear();
    }

    /**
     * Disconnect from the server.
     */
    public void disconnect() {
        if (socket != null) {
            socket.emit("leaveRoom");
            socket.disconnect();
            socket.off();
            socket = null;
        }
        currentRoom = null;
        currentUsername = null;
        onlineUsers.clear();
    }

    public boolean isConnected() {
        return socket != null && socket.connected();
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public List<String> getOnlineUsers() {
        return new ArrayList<>(onlineUsers);
    }

    // ─── Socket.IO event listeners ───────────────────────────────────────────

    private void setupListeners() {
        socket.on(Socket.EVENT_CONNECT, args -> {
            Log.d(TAG, "Connected to server");
        });

        socket.on(Socket.EVENT_CONNECT_ERROR, args -> {
            Log.e(TAG, "Connect error: " + (args.length > 0 ? args[0] : "unknown"));
            if (handler != null) {
                new Handler(Looper.getMainLooper()).post(() -> handler.obtainMessage(Constants.MSG_CONNECTION_FAILED,
                        "Server connection failed").sendToTarget());
            }
        });

        // Successfully joined room
        socket.on("joinedRoom", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                currentUsername = data.getString("username");
                String roomName = data.getString("roomName");
                JSONArray users = data.getJSONArray("users");

                onlineUsers.clear();
                for (int i = 0; i < users.length(); i++) {
                    onlineUsers.add(users.getString(i));
                }

                Log.d(TAG, "Joined room: " + roomName + " (" + onlineUsers.size() + " users)");

                if (handler != null) {
                    new Handler(Looper.getMainLooper())
                            .post(() -> handler.obtainMessage(Constants.MSG_CONNECTED, currentUsername).sendToTarget());
                }
            } catch (Exception e) {
                Log.e(TAG, "joinedRoom parse error", e);
            }
        });

        // Auth error
        socket.on("authError", args -> {
            String error = args.length > 0 ? args[0].toString() : "Auth failed";
            Log.e(TAG, "Auth error: " + error);
            if (handler != null) {
                new Handler(Looper.getMainLooper())
                        .post(() -> handler.obtainMessage(Constants.MSG_CONNECTION_FAILED, error).sendToTarget());
            }
        });

        // User joined
        socket.on("userJoined", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String username = data.getString("username");
                updateUserList(data.getJSONArray("users"));

                if (handler != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        handler.obtainMessage(Constants.MSG_READ,
                                Constants.PROTO_JOIN + username).sendToTarget();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "userJoined parse error", e);
            }
        });

        // User left
        socket.on("userLeft", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String username = data.getString("username");
                updateUserList(data.getJSONArray("users"));

                if (handler != null) {
                    new Handler(Looper.getMainLooper()).post(() -> {
                        handler.obtainMessage(Constants.MSG_READ,
                                Constants.PROTO_LEAVE + username).sendToTarget();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "userLeft parse error", e);
            }
        });

        // Chat message received
        socket.on("chatMessage", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String encMsg = data.getString("encryptedMessage");
                String sender = data.getString("senderName");

                // Pass as MSG:sender:encryptedContent
                // OnlineChatActivity will decrypt locally
                if (handler != null) {
                    new Handler(Looper.getMainLooper()).post(() -> handler.obtainMessage(Constants.MSG_READ,
                            "ENCRYPTED_MSG:" + sender + ":" + encMsg).sendToTarget());
                }
            } catch (Exception e) {
                Log.e(TAG, "chatMessage parse error", e);
            }
        });

        // File received
        socket.on("fileShared", args -> {
            try {
                JSONObject data = (JSONObject) args[0];
                String sender = data.getString("senderName");
                String fileName = data.getString("fileName");
                String mimeType = data.getString("mimeType");
                String fileData = data.getString("fileData");

                if (fileCallback != null) {
                    new Handler(Looper.getMainLooper())
                            .post(() -> fileCallback.onFileReceived(sender, fileName, mimeType, fileData));
                }
            } catch (Exception e) {
                Log.e(TAG, "fileShared parse error", e);
            }
        });

        socket.on(Socket.EVENT_DISCONNECT, args -> {
            Log.d(TAG, "Disconnected from server");
        });
    }

    private void updateUserList(JSONArray users) {
        onlineUsers.clear();
        try {
            for (int i = 0; i < users.length(); i++) {
                onlineUsers.add(users.getString(i));
            }
        } catch (Exception e) {
            Log.e(TAG, "updateUserList error", e);
        }
    }
}

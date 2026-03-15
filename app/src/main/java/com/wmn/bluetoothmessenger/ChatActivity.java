package com.wmn.bluetoothmessenger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.OpenableColumns;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmn.bluetoothmessenger.bluetooth.BluetoothService;
import com.wmn.bluetoothmessenger.manager.GroupManager;
import com.wmn.bluetoothmessenger.manager.MessageManager;
import com.wmn.bluetoothmessenger.manager.SessionManager;
import com.wmn.bluetoothmessenger.model.ChatMessage;
import com.wmn.bluetoothmessenger.util.Constants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Real-time chat activity for the Bluetooth messaging group.
 *
 * Features:
 * - Real-time message exchange via Bluetooth sockets
 * - Messages auto-disappear after 1 minute (TTL)
 * - Group terminates after 30 minutes of inactivity
 * - In-memory message storage only (ephemeral)
 * - Broadcast messaging to all connected peers
 */
public class ChatActivity extends AppCompatActivity {

    private DrawerLayout drawerLayout;
    private RecyclerView rvMessages, rvUsers;
    private EditText etMessage;
    private Button btnSend, btnAttach;
    private TextView tvGroupName, tvMemberCount, btnLeave, btnBack, btnChangePassword, btnMenu;
    private TextView drawerMemberCount, drawerMyName;

    // ── Fields ────────────────────────────────────────────────────────────────
    // (bluetoothService is obtained from the singleton; no local new
    // BluetoothService())
    // ─────────────────────────────────────────────────────────────────────────
    private BluetoothService bluetoothService;
    private GroupManager groupManager;
    private MessageManager messageManager;
    private SessionManager sessionManager;

    private MessageAdapter messageAdapter;
    private UserAdapter userAdapter;
    private final List<ChatMessage> displayMessages = new ArrayList<>();
    private final List<String> memberUsers = new ArrayList<>();

    private String myDeviceName;
    private String groupName;
    private boolean isHost;

    /** Password hash — kept for auto-reconnect after host migration. */
    private String passwordHash = "";

    /**
     * Set by HOST_CHANGED: the Bluetooth address of the new host to reconnect to.
     */
    private String pendingNewHostAddress = null;

    /**
     * True while auto-reconnect is in progress (suppresses normal disconnect
     * handling).
     */
    private boolean reconnecting = false;

    private static final int MAX_FILE_SIZE = 512 * 1024; // 512 KB for Bluetooth transfer
    private ActivityResultLauncher<Intent> filePickerLauncher;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_chat);

        // Get intent extras
        groupName = getIntent().getStringExtra(Constants.EXTRA_GROUP_NAME);
        isHost = getIntent().getBooleanExtra(Constants.EXTRA_IS_HOST, false);

        if (groupName == null)
            groupName = "Group";

        // Init views
        drawerLayout = findViewById(R.id.drawer_layout);
        rvMessages = findViewById(R.id.rv_messages);
        rvUsers = findViewById(R.id.rv_users);
        etMessage = findViewById(R.id.et_message);
        btnSend = findViewById(R.id.btn_send);
        btnAttach = findViewById(R.id.btn_attach);
        tvGroupName = findViewById(R.id.tv_group_name);
        tvMemberCount = findViewById(R.id.tv_member_count);
        btnLeave = findViewById(R.id.btn_leave);
        btnBack = findViewById(R.id.btn_back);
        btnMenu = findViewById(R.id.btn_menu);
        btnChangePassword = findViewById(R.id.btn_change_password);
        drawerMemberCount = findViewById(R.id.drawer_member_count);
        drawerMyName = findViewById(R.id.drawer_my_name);
        // Only the host can see / use the change-password button
        btnChangePassword.setVisibility(isHost ? View.VISIBLE : View.GONE);

        tvGroupName.setText(groupName);

        // Get device name – prefer explicit username from room form when present.
        String typedUsername = getIntent().getStringExtra(Constants.EXTRA_USERNAME);
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        try {
            myDeviceName = typedUsername;
            if (myDeviceName == null || myDeviceName.trim().isEmpty()) {
                myDeviceName = bluetoothAdapter.getName();
            }
            // If this is the host, the adapter name may have been changed to
            // "GhostLink_<room>" for discoverability — strip the prefix so
            // chat messages show the real device name.
            if (myDeviceName != null && myDeviceName.startsWith(Constants.BT_SERVICE_PREFIX)) {
                // Use the original name stored in BluetoothService
                BluetoothService svc = BluetoothService.getInstance();
                if (svc != null && svc.getOriginalAdapterName() != null) {
                    myDeviceName = svc.getOriginalAdapterName();
                } else {
                    // Fallback: strip the prefix
                    myDeviceName = myDeviceName.substring(Constants.BT_SERVICE_PREFIX.length());
                }
            }
            if (myDeviceName == null)
                myDeviceName = "Me";
        } catch (SecurityException e) {
            myDeviceName = "Me";
        }

        // Setup RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        messageAdapter = new MessageAdapter();
        rvMessages.setAdapter(messageAdapter);

        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        userAdapter = new UserAdapter();
        rvUsers.setAdapter(userAdapter);

        // Setup managers
        setupGroupManager();
        setupMessageManager();
        setupSessionManager();
        setupBluetoothService();

        // Button listeners
        btnSend.setOnClickListener(v -> sendMessage());
        btnLeave.setOnClickListener(v -> confirmLeave());
        btnBack.setOnClickListener(v -> confirmLeave());
        btnMenu.setOnClickListener(v -> drawerLayout.openDrawer(findViewById(R.id.nav_drawer)));
        findViewById(R.id.drawer_btn_leave).setOnClickListener(v -> confirmLeave());
        if (isHost) {
            btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());
        }
        btnAttach.setOnClickListener(v -> openFilePicker());

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        handleFileSelected(result.getData().getData());
                    }
                });

        // Handle IME send action
        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        // Add welcome system message
        addSystemMessage("Welcome to " + groupName + "! 🔒");
        addSystemMessage("Messages auto-delete after 1 min");
        updateMemberCount();
    }

    private void setupGroupManager() {
        groupManager = new GroupManager();
        // Store password hash for potential host migration reconnect
        passwordHash = getIntent().getStringExtra(Constants.EXTRA_PASSWORD_HASH);
        if (passwordHash == null)
            passwordHash = "";

        if (isHost) {
            groupManager.createGroupWithHash(groupName, passwordHash, myDeviceName);
        } else {
            groupManager.setJoinedGroup(groupName, myDeviceName);
        }
    }

    private void setupMessageManager() {
        messageManager = new MessageManager();
        messageManager.setListener(new MessageManager.MessageListener() {
            @Override
            public void onMessageAdded(ChatMessage message, int position) {
                uiHandler.post(() -> {
                    displayMessages.add(message);
                    messageAdapter.notifyItemInserted(displayMessages.size() - 1);
                    rvMessages.scrollToPosition(displayMessages.size() - 1);
                });
            }

            @Override
            public void onMessageRemoved(int position) {
                uiHandler.post(() -> {
                    if (position < displayMessages.size()) {
                        displayMessages.remove(position);
                        messageAdapter.notifyItemRemoved(position);
                    }
                });
            }

            @Override
            public void onMessagesChanged() {
                uiHandler.post(() -> {
                    displayMessages.clear();
                    displayMessages.addAll(messageManager.getMessages());
                    messageAdapter.notifyDataSetChanged();
                });
            }
        });
        messageManager.startTTLCleanup();
    }

    private void setupSessionManager() {
        sessionManager = new SessionManager();
        sessionManager.setListener(new SessionManager.SessionListener() {
            @Override
            public void onSessionTimeout() {
                uiHandler.post(() -> {
                    addSystemMessage("⏰ Session ended — 30 min inactivity");
                    Toast.makeText(ChatActivity.this,
                            R.string.session_timeout, Toast.LENGTH_LONG).show();

                    // Disconnect and go back
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        leaveGroup();
                    }, 2000);
                });
            }

            @Override
            public void onSessionWarning(long remainingMs) {
                long mins = remainingMs / 60000;
                uiHandler.post(() -> {
                    addSystemMessage("⚠️ Session expires in " + mins + " min (no activity)");
                });
            }
        });
        sessionManager.startMonitoring();
    }

    private void setupBluetoothService() {
        // Attach to the LIVE singleton — do not create a new instance.
        bluetoothService = BluetoothService.getInstance();
        if (bluetoothService == null) {
            Toast.makeText(this, "Bluetooth session lost. Please restart.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // ── Step 1: configure auth BEFORE swapping the handler ────────────────
        // This closes the window where an incoming connection could be checked
        // against the old (CreateGroupActivity) callback.
        if (isHost) {
            String passwordHash = getIntent().getStringExtra(Constants.EXTRA_PASSWORD_HASH);
            if (passwordHash == null)
                passwordHash = "";
            bluetoothService.setPasswordHash(passwordHash);
            bluetoothService.setAuthCallback(new BluetoothService.AuthCallback() {
                @Override
                public boolean onAuthRequest(String receivedHash) {
                    return groupManager.authenticate(receivedHash);
                }

                @Override
                public void onAuthSuccess(String deviceName) {
                    // MSG_CONNECTED handler already calls groupManager.addMember().
                    // Do NOT call it again here to avoid duplicate member entries.
                    sessionManager.resetActivity();
                }

                @Override
                public void onAuthFail(String deviceName) {
                    uiHandler.post(() -> addSystemMessage("\uD83D\uDEAB Auth failed for: " + deviceName));
                }
            });
            // AcceptThread is ALREADY running inside the singleton from
            // CreateGroupActivity.
            // Do NOT call startHosting() again — it would discard all current connections.
        }

        // ── Step 2: swap the UI handler (propagates to every ConnectedThread) ─
        Handler btHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MSG_READ:
                        handleReceivedMessage((String) msg.obj);
                        break;

                    // MSG_CONNECTED is handled after MSG_DISCONNECTED below

                    case Constants.MSG_DISCONNECTED:
                        String leftDevice = (String) msg.obj;
                        // If we have a pending host migration → auto-reconnect
                        if (pendingNewHostAddress != null && !isHost && !reconnecting) {
                            reconnecting = true;
                            addSystemMessage("🔄 Host migrating, reconnecting...");
                            autoReconnectToNewHost();
                        } else if (groupManager.getMembers().contains(leftDevice)) {
                            groupManager.removeMember(leftDevice);
                            addSystemMessage("\uD83D\uDC4B " + leftDevice + " disconnected");
                            updateMemberCount();
                        }
                        break;

                    case Constants.MSG_CONNECTED:
                        if (reconnecting) {
                            // Reconnection after migration succeeded
                            reconnecting = false;
                            pendingNewHostAddress = null;
                            addSystemMessage("✅ Reconnected to new host");
                            updateMemberCount();
                        } else if (isHost) {
                            String deviceName = (String) msg.obj;
                            groupManager.addMember(deviceName);
                            addSystemMessage("\uD83D\uDCF1 " + deviceName + " joined");
                            updateMemberCount();
                            sessionManager.resetActivity();
                        }
                        break;

                    case Constants.MSG_CONNECTION_FAILED:
                        if (reconnecting) {
                            // Retry after a short delay
                            String failReason = (String) msg.obj;
                            Log.d("ChatActivity", "Reconnect failed: " + failReason + ", retrying...");
                            uiHandler.postDelayed(() -> autoReconnectToNewHost(), 2000);
                        }
                        break;
                }
            }
        };
        bluetoothService.setHandler(btHandler);

        // ── Step 3: seed groupManager with pre-existing connections ───────────
        for (String name : bluetoothService.getConnectedDeviceNames()) {
            groupManager.addMember(name);
        }
        updateMemberCount();
    }

    /**
     * Process received Bluetooth protocol messages.
     */
    private void handleReceivedMessage(String rawMessage) {
        if (rawMessage == null)
            return;

        sessionManager.resetActivity();

        if (rawMessage.startsWith(Constants.PROTO_MSG)) {
            // Chat message: MSG:SenderName:Content
            String payload = rawMessage.substring(Constants.PROTO_MSG.length());
            int colonIdx = payload.indexOf(":");
            if (colonIdx > 0) {
                String sender = payload.substring(0, colonIdx);
                String content = payload.substring(colonIdx + 1);

                // Skip messages from ourselves — the host rebroadcasts to ALL
                // clients including the original sender, so without this check
                // the sender would see their own message twice (once as "sent",
                // once as "received").
                if (sender.equals(myDeviceName)) {
                    return;
                }

                ChatMessage msg = ChatMessage.createMessage(sender, content, false);
                messageManager.addMessage(msg);

                // If host, rebroadcast to all other clients
                if (isHost) {
                    bluetoothService.broadcastMessage(rawMessage);
                }
            }
        } else if (rawMessage.startsWith(Constants.PROTO_FILE)) {
            // FILE:sender:nameBase64:mimeBase64:dataBase64
            String payload = rawMessage.substring(Constants.PROTO_FILE.length());
            String[] parts = payload.split(":", 4);
            if (parts.length == 4) {
                String sender = parts[0];
                if (!sender.equals(myDeviceName)) {
                    try {
                        String fileName = new String(Base64.decode(parts[1], Base64.NO_WRAP));
                        String mimeType = new String(Base64.decode(parts[2], Base64.NO_WRAP));
                        byte[] fileBytes = Base64.decode(parts[3], Base64.NO_WRAP);
                        File saved = saveToDownloads(fileName, fileBytes);

                        if (saved != null) {
                            addSystemMessage("📎 " + sender + " shared: " + fileName);
                            addSystemMessage("Saved: " + saved.getName());
                        } else {
                            addSystemMessage("📎 " + sender + " shared: " + fileName + " (save failed)");
                        }
                    } catch (Exception e) {
                        addSystemMessage("📎 Failed to receive shared file");
                    }
                }

                if (isHost) {
                    bluetoothService.broadcastMessage(rawMessage);
                }
            }
        } else if (rawMessage.startsWith(Constants.PROTO_JOIN)) {
            // BUG FIX: Host learns of new members via MSG_CONNECTED (not via PROTO_JOIN
            // broadcast).
            // Clients learn of OTHER members via PROTO_JOIN. Also skip self-join
            // notifications.
            if (!isHost) {
                String deviceName = rawMessage.substring(Constants.PROTO_JOIN.length());
                if (!deviceName.equals(myDeviceName)) {
                    groupManager.addMember(deviceName);
                    addSystemMessage("\uD83D\uDCF1 " + deviceName + " joined");
                    updateMemberCount();
                }
            }
        } else if (rawMessage.startsWith(Constants.PROTO_LEAVE)) {
            String deviceName = rawMessage.substring(Constants.PROTO_LEAVE.length());
            // Only process if the member is still tracked (dedup with MSG_DISCONNECTED)
            if (groupManager.getMembers().contains(deviceName)) {
                groupManager.removeMember(deviceName);
                addSystemMessage("👋 " + deviceName + " left");
                updateMemberCount();
            }
        } else if (rawMessage.equals(Constants.PROTO_SESSION_END)) {
            addSystemMessage("⏰ Group session ended by host");
            Toast.makeText(this, R.string.session_timeout, Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(this::leaveGroup, 2000);

        } else if (rawMessage.startsWith(Constants.PROTO_PROMOTE_HOST)) {
            // This device has been chosen to be the new host
            String payload = rawMessage.substring(Constants.PROTO_PROMOTE_HOST.length());
            int colonIdx = payload.indexOf(":");
            if (colonIdx > 0) {
                String roomName = payload.substring(0, colonIdx);
                String newPasswordHash = payload.substring(colonIdx + 1);

                addSystemMessage("\uD83D\uDC51 You are now the host of " + roomName);

                // Switch to host mode
                isHost = true;
                groupName = roomName;
                passwordHash = newPasswordHash;
                tvGroupName.setText(groupName);

                // Show change-password button for new host
                btnChangePassword.setVisibility(View.VISIBLE);
                btnChangePassword.setOnClickListener(v -> showChangePasswordDialog());

                // Set up GroupManager as host
                groupManager.clearGroup();
                groupManager.createGroupWithHash(roomName, newPasswordHash, myDeviceName);

                // Switch BluetoothService to host mode
                bluetoothService.switchToHost(roomName, newPasswordHash);

                // Re-set auth callback for the new host
                bluetoothService.setAuthCallback(new BluetoothService.AuthCallback() {
                    @Override
                    public boolean onAuthRequest(String receivedHash) {
                        return groupManager.authenticate(receivedHash);
                    }

                    @Override
                    public void onAuthSuccess(String deviceName) {
                        uiHandler.post(() -> {
                            groupManager.addMember(deviceName);
                            updateMemberCount();
                        });
                    }

                    @Override
                    public void onAuthFail(String deviceName) {
                        uiHandler.post(() -> addSystemMessage("\uD83D\uDEAB Auth failed for: " + deviceName));
                    }
                });

                updateMemberCount();
            }

        } else if (rawMessage.startsWith(Constants.PROTO_HOST_CHANGED)) {
            // Another member has been promoted; store their address for auto-reconnect
            String newHostAddr = rawMessage.substring(Constants.PROTO_HOST_CHANGED.length());
            pendingNewHostAddress = newHostAddr;
            addSystemMessage("\uD83D\uDD04 Host is migrating...");
        }
    }

    /**
     * Send a chat message to all connected peers.
     */
    private void sendMessage() {
        String content = etMessage.getText().toString().trim();
        if (content.isEmpty())
            return;

        // Add to local display
        ChatMessage msg = ChatMessage.createMessage(myDeviceName, content, true);
        messageManager.addMessage(msg);

        // Broadcast via Bluetooth
        bluetoothService.sendChatMessage(myDeviceName, content);

        // Reset session timer
        sessionManager.resetActivity();

        // Clear input
        etMessage.setText("");
    }

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void handleFileSelected(Uri uri) {
        if (uri == null) {
            return;
        }

        try {
            ContentResolver resolver = getContentResolver();

            String fileName = "file";
            Cursor cursor = resolver.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    fileName = cursor.getString(idx);
                }
                cursor.close();
            }

            String mimeType = resolver.getType(uri);
            if (mimeType == null) {
                mimeType = "application/octet-stream";
            }

            InputStream is = resolver.openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, "Could not open file", Toast.LENGTH_SHORT).show();
                return;
            }

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            is.close();

            byte[] fileBytes = bos.toByteArray();
            if (fileBytes.length > MAX_FILE_SIZE) {
                Toast.makeText(this, "File too large for Bluetooth (max 512 KB)", Toast.LENGTH_LONG).show();
                return;
            }

            String nameBase64 = Base64.encodeToString(fileName.getBytes(), Base64.NO_WRAP);
            String mimeBase64 = Base64.encodeToString(mimeType.getBytes(), Base64.NO_WRAP);
            String dataBase64 = Base64.encodeToString(fileBytes, Base64.NO_WRAP);

            String message = Constants.PROTO_FILE + myDeviceName + ":" + nameBase64 + ":" + mimeBase64 + ":" + dataBase64;

            bluetoothService.broadcastMessage(message);
            addSystemMessage("📎 You shared: " + fileName);
            sessionManager.resetActivity();
        } catch (Exception e) {
            Log.e("ChatActivity", "File send failed", e);
            Toast.makeText(this, "Failed to send file", Toast.LENGTH_SHORT).show();
        }
    }

    private File saveToDownloads(String fileName, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, "GhostLink_" + fileName);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GhostLink");

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    return null;
                }

                OutputStream os = getContentResolver().openOutputStream(uri);
                if (os == null) {
                    return null;
                }
                os.write(data);
                os.flush();
                os.close();

                return new File("GhostLink_" + fileName);
            }

            File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            File out = new File(dir, "GhostLink_" + fileName);
            FileOutputStream fos = new FileOutputStream(out);
            fos.write(data);
            fos.close();
            return out;
        } catch (Exception e) {
            Log.e("ChatActivity", "Save file failed", e);
            return null;
        }
    }

    private void addSystemMessage(String text) {
        ChatMessage msg = ChatMessage.createSystemMessage(text);
        messageManager.addMessage(msg);
    }

    private void updateMemberCount() {
        int count = groupManager.getMemberCount();
        String text = count + (count == 1 ? " member" : " members");
        tvMemberCount.setText(text);
        drawerMemberCount.setText(text);

        memberUsers.clear();
        memberUsers.addAll(groupManager.getMembers());
        if (myDeviceName != null && !myDeviceName.isEmpty() && !memberUsers.contains(myDeviceName)) {
            memberUsers.add(0, myDeviceName);
        }
        drawerMyName.setText(myDeviceName);
        userAdapter.notifyDataSetChanged();
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Leave Group")
                .setMessage("Are you sure you want to leave?")
                .setPositiveButton("Leave", (d, w) -> leaveGroup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Host-only: change the room password.
     * Updates BluetoothService + GroupManager so new joiners are authenticated
     * against the new password. Existing members stay connected.
     */
    private void showChangePasswordDialog() {
        EditText etNew = new EditText(this);
        etNew.setHint("New password (min 4 chars)");
        etNew.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etNew.setPadding(48, 32, 48, 32);

        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Change Room Password")
                .setMessage("Only new joiners will need the new password. Current members stay connected.")
                .setView(etNew)
                .setPositiveButton("Change", (dialog, which) -> {
                    String newPw = etNew.getText().toString().trim();
                    if (newPw.length() < 4) {
                        Toast.makeText(this, "Password must be at least 4 characters",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String newHash = com.wmn.bluetoothmessenger.model.GroupInfo.hashPassword(newPw);
                    // Update the in-memory auth state on the host
                    groupManager.updatePasswordHash(newHash);
                    bluetoothService.setPasswordHash(newHash);
                    // Inform all current members via a system message
                    bluetoothService.broadcastMessage(
                            com.wmn.bluetoothmessenger.util.Constants.PROTO_MSG
                                    + "System:Room password changed by host");
                    addSystemMessage("\uD83D\uDD11 Room password updated");
                    Toast.makeText(this, "Password changed", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void leaveGroup() {
        if (bluetoothService != null) {
            if (isHost && bluetoothService.getConnectedCount() > 0) {
                // ── Host migration: promote a member before leaving ──
                String promoted = bluetoothService.initiateHostMigration(
                        groupName, passwordHash, myDeviceName);

                if (promoted != null) {
                    addSystemMessage("\uD83D\uDC51 Promoted " + promoted + " to host");
                }

                // Give the promoted member time to start their server,
                // then disconnect quietly (no SESSION_END).
                uiHandler.postDelayed(() -> {
                    messageManager.shutdown();
                    sessionManager.shutdown();
                    groupManager.clearGroup();

                    BluetoothService svc = BluetoothService.getInstance();
                    if (svc != null) {
                        svc.disconnectQuietly();
                    }
                    BluetoothService.destroyInstance();
                    finish();
                }, 1500); // 1.5s delay for migration
                return;
            }

            // Non-host or no members: normal leave
            bluetoothService.broadcastMessage(Constants.PROTO_LEAVE + myDeviceName);
        }

        messageManager.shutdown();
        sessionManager.shutdown();
        groupManager.clearGroup();
        BluetoothService.destroyInstance();
        finish();
    }

    /**
     * Auto-reconnect to the new host after host migration.
     */
    @SuppressWarnings("MissingPermission")
    private void autoReconnectToNewHost() {
        if (pendingNewHostAddress == null || bluetoothService == null) {
            reconnecting = false;
            return;
        }

        try {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice newHost = btAdapter.getRemoteDevice(pendingNewHostAddress);
            bluetoothService.connectToHostWithHash(newHost, passwordHash);
        } catch (Exception e) {
            addSystemMessage("❌ Failed to reconnect: " + e.getMessage());
            reconnecting = false;
            pendingNewHostAddress = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(findViewById(R.id.nav_drawer))) {
            drawerLayout.closeDrawers();
            return;
        }
        confirmLeave();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            messageManager.shutdown();
            sessionManager.shutdown();
        } catch (Exception ignored) {
        }
        // Do NOT destroy the singleton here — leaveGroup() handles that explicitly.
        // This prevents premature teardown on orientation change / back-stack pop.
    }

    // ========== Message Adapter ==========

    private class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

        private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

        class ViewHolder extends RecyclerView.ViewHolder {
            // Received
            View layoutReceived;
            TextView tvSenderName, tvMessageReceived, tvTimeReceived;
            // Sent
            View layoutSent;
            TextView tvMessageSent, tvTimeSent;
            // System
            TextView tvSystemMessage;

            ViewHolder(View v) {
                super(v);
                layoutReceived = v.findViewById(R.id.layout_received);
                tvSenderName = v.findViewById(R.id.tv_sender_name);
                tvMessageReceived = v.findViewById(R.id.tv_message_received);
                tvTimeReceived = v.findViewById(R.id.tv_time_received);
                layoutSent = v.findViewById(R.id.layout_sent);
                tvMessageSent = v.findViewById(R.id.tv_message_sent);
                tvTimeSent = v.findViewById(R.id.tv_time_sent);
                tvSystemMessage = v.findViewById(R.id.tv_system_message);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_message, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            ChatMessage msg = displayMessages.get(position);
            String time = timeFormat.format(new Date(msg.getTimestamp()));

            // Hide all layouts first
            holder.layoutReceived.setVisibility(View.GONE);
            holder.layoutSent.setVisibility(View.GONE);
            holder.tvSystemMessage.setVisibility(View.GONE);

            if (msg.getType() == ChatMessage.TYPE_SYSTEM) {
                // System message
                holder.tvSystemMessage.setVisibility(View.VISIBLE);
                holder.tvSystemMessage.setText(msg.getContent());
            } else if (msg.isMine()) {
                // Sent message
                holder.layoutSent.setVisibility(View.VISIBLE);
                holder.tvMessageSent.setText(msg.getContent());
                holder.tvTimeSent.setText(time);
            } else {
                // Received message
                holder.layoutReceived.setVisibility(View.VISIBLE);
                holder.tvSenderName.setText(msg.getSenderName());
                holder.tvMessageReceived.setText(msg.getContent());
                holder.tvTimeReceived.setText(time);
            }
        }

        @Override
        public int getItemCount() {
            return displayMessages.size();
        }
    }

    private class UserAdapter extends RecyclerView.Adapter<UserAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvUsername;

            ViewHolder(View itemView) {
                super(itemView);
                tvUsername = itemView.findViewById(R.id.tv_username);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_user, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            String name = memberUsers.get(position);
            holder.tvUsername.setText(name);
            if (name.equals(myDeviceName)) {
                holder.tvUsername.setTextColor(ContextCompat.getColor(ChatActivity.this, R.color.accent));
            } else {
                holder.tvUsername.setTextColor(ContextCompat.getColor(ChatActivity.this, R.color.text_primary));
            }
        }

        @Override
        public int getItemCount() {
            return memberUsers.size();
        }
    }
}

package com.wmn.bluetoothmessenger;

import android.app.Activity;
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
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.core.content.ContextCompat;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmn.bluetoothmessenger.model.ChatMessage;
import com.wmn.bluetoothmessenger.network.SocketService;
import com.wmn.bluetoothmessenger.util.Constants;
import com.wmn.bluetoothmessenger.util.CryptoUtil;

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

import javax.crypto.SecretKey;

/**
 * Online chat activity with DrawerLayout sidebar, E2E encryption,
 * and document sharing.
 */
public class OnlineChatActivity extends AppCompatActivity {

    private static final String TAG = "OnlineChatActivity";
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024; // 5 MB

    // UI
    private DrawerLayout drawerLayout;
    private RecyclerView rvMessages, rvUsers;
    private EditText etMessage;
    private TextView tvRoomName, tvOnlineCount;
    private TextView drawerRoomName, drawerOnlineCount, drawerMyName;
    private MessageAdapter messageAdapter;
    private UserAdapter userAdapter;

    // Data
    private String roomName, username, passwordHash;
    private SecretKey encryptionKey;
    private SocketService socketService;
    private final List<ChatMessage> displayMessages = new ArrayList<>();
    private final List<String> onlineUsers = new ArrayList<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // File picker
    private ActivityResultLauncher<Intent> filePickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_chat);

        // Get intent data
        roomName = getIntent().getStringExtra("room_name");
        username = getIntent().getStringExtra("username");
        passwordHash = getIntent().getStringExtra("password");

        // Derive encryption key from the password hash
        encryptionKey = CryptoUtil.deriveKey(passwordHash);

        // Find views
        drawerLayout = findViewById(R.id.drawer_layout);
        rvMessages = findViewById(R.id.rv_messages);
        rvUsers = findViewById(R.id.rv_users);
        etMessage = findViewById(R.id.et_message);
        tvRoomName = findViewById(R.id.tv_room_name);
        tvOnlineCount = findViewById(R.id.tv_online_count);
        drawerRoomName = findViewById(R.id.drawer_room_name);
        drawerOnlineCount = findViewById(R.id.drawer_online_count);
        drawerMyName = findViewById(R.id.drawer_my_name);

        // Setup RecyclerView
        LinearLayoutManager msgLayout = new LinearLayoutManager(this);
        msgLayout.setStackFromEnd(true);
        rvMessages.setLayoutManager(msgLayout);
        messageAdapter = new MessageAdapter();
        rvMessages.setAdapter(messageAdapter);

        rvUsers.setLayoutManager(new LinearLayoutManager(this));
        userAdapter = new UserAdapter();
        rvUsers.setAdapter(userAdapter);

        // Set room info
        tvRoomName.setText("ROOM // " + roomName);
        drawerRoomName.setText(roomName);
        drawerMyName.setText(username);

        // Button listeners
        findViewById(R.id.btn_menu).setOnClickListener(v -> drawerLayout.openDrawer(findViewById(R.id.nav_drawer)));
        findViewById(R.id.btn_leave).setOnClickListener(v -> confirmLeave());
        findViewById(R.id.drawer_btn_leave).setOnClickListener(v -> confirmLeave());
        findViewById(R.id.btn_send).setOnClickListener(v -> sendMessage());
        findViewById(R.id.btn_attach).setOnClickListener(v -> openFilePicker());

        etMessage.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        // File picker
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        handleFileSelected(result.getData().getData());
                    }
                });

        // Back press handling
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(findViewById(R.id.nav_drawer))) {
                    drawerLayout.closeDrawers();
                } else {
                    confirmLeave();
                }
            }
        });

        // Setup socket handler
        setupSocketService();

        // Welcome message
        addSystemMessage("Welcome to " + roomName + "! 🔒");
        addSystemMessage("End-to-end encrypted • Share files with 📎");

        // Seed initial user list
        socketService = SocketService.getInstance();
        onlineUsers.clear();
        onlineUsers.addAll(socketService.getOnlineUsers());
        updateOnlineCount();
        userAdapter.notifyDataSetChanged();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Socket.IO handler
    // ═══════════════════════════════════════════════════════════════════════════

    private void setupSocketService() {
        socketService = SocketService.getInstance();

        socketService.setHandler(new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MSG_READ:
                        handleIncomingMessage((String) msg.obj);
                        break;

                    case Constants.MSG_DISCONNECTED:
                        addSystemMessage("⚠️ Disconnected from server");
                        break;

                    case Constants.MSG_CONNECTION_FAILED:
                        addSystemMessage("❌ Connection error: " + msg.obj);
                        break;
                }
            }
        });

        // File callback
        socketService.setFileCallback((senderName, fileName, mimeType, fileData) -> {
            // Decrypt file
            byte[] decrypted = CryptoUtil.decryptBytes(fileData, encryptionKey);
            if (decrypted != null) {
                // Save to downloads
                File saved = saveToDownloads(fileName, decrypted);
                if (saved != null) {
                    addSystemMessage("📎 " + senderName + " shared: " + fileName);
                    addSystemMessage("   Saved to: " + saved.getName());
                } else {
                    addSystemMessage("📎 " + senderName + " shared: " + fileName + " (save failed)");
                }
            } else {
                addSystemMessage("📎 " + senderName + " shared: " + fileName + " (decrypt failed)");
            }
        });
    }

    private void handleIncomingMessage(String raw) {
        if (raw.startsWith(Constants.PROTO_JOIN)) {
            String who = raw.substring(Constants.PROTO_JOIN.length());
            addSystemMessage("📱 " + who + " joined");
            refreshUserList();

        } else if (raw.startsWith(Constants.PROTO_LEAVE)) {
            String who = raw.substring(Constants.PROTO_LEAVE.length());
            addSystemMessage("👋 " + who + " left");
            refreshUserList();

        } else if (raw.startsWith("ENCRYPTED_MSG:")) {
            // Format: ENCRYPTED_MSG:sender:encryptedContent
            String rest = raw.substring("ENCRYPTED_MSG:".length());
            int colonIdx = rest.indexOf(":");
            if (colonIdx > 0) {
                String sender = rest.substring(0, colonIdx);
                String encryptedContent = rest.substring(colonIdx + 1);

                String decrypted = CryptoUtil.decrypt(encryptedContent, encryptionKey);
                if (decrypted != null) {
                    ChatMessage chatMsg = ChatMessage.createMessage(sender, decrypted, false);
                    addChatMessage(chatMsg);
                } else {
                    addSystemMessage("🔒 " + sender + " sent an encrypted message (could not decrypt)");
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Messages
    // ═══════════════════════════════════════════════════════════════════════════

    private void sendMessage() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty())
            return;

        // Encrypt
        String encrypted = CryptoUtil.encrypt(text, encryptionKey);
        if (encrypted == null) {
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show();
            return;
        }

        // Send encrypted
        socketService.sendMessage(encrypted);

        // Show locally (unencrypted, as "mine")
        ChatMessage myMsg = ChatMessage.createMessage(username, text, true);
        addChatMessage(myMsg);

        etMessage.setText("");
    }

    private void addChatMessage(ChatMessage msg) {
        displayMessages.add(msg);
        messageAdapter.notifyItemInserted(displayMessages.size() - 1);
        rvMessages.scrollToPosition(displayMessages.size() - 1);
    }

    private void addSystemMessage(String text) {
        ChatMessage sysMsg = ChatMessage.createSystemMessage(text);
        displayMessages.add(sysMsg);
        messageAdapter.notifyItemInserted(displayMessages.size() - 1);
        rvMessages.scrollToPosition(displayMessages.size() - 1);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // User list
    // ═══════════════════════════════════════════════════════════════════════════

    private void refreshUserList() {
        onlineUsers.clear();
        onlineUsers.addAll(socketService.getOnlineUsers());
        userAdapter.notifyDataSetChanged();
        updateOnlineCount();
    }

    private void updateOnlineCount() {
        String text = "🟢 " + onlineUsers.size() + " online";
        tvOnlineCount.setText(text);
        drawerOnlineCount.setText(text);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // File sharing
    // ═══════════════════════════════════════════════════════════════════════════

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void handleFileSelected(Uri uri) {
        if (uri == null)
            return;

        try {
            ContentResolver cr = getContentResolver();

            // Get file name
            String fileName = "file";
            Cursor cursor = cr.query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0)
                    fileName = cursor.getString(idx);
                cursor.close();
            }

            // Get mime type
            String mimeType = cr.getType(uri);
            if (mimeType == null)
                mimeType = "application/octet-stream";

            // Read bytes
            InputStream is = cr.openInputStream(uri);
            if (is == null)
                return;

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int read;
            while ((read = is.read(buf)) != -1) {
                bos.write(buf, 0, read);
            }
            is.close();
            byte[] fileBytes = bos.toByteArray();

            if (fileBytes.length > MAX_FILE_SIZE) {
                Toast.makeText(this, "File too large (max 5 MB)", Toast.LENGTH_SHORT).show();
                return;
            }

            // Encrypt
            String encrypted = CryptoUtil.encryptBytes(fileBytes, encryptionKey);
            if (encrypted == null) {
                Toast.makeText(this, "Encryption failed", Toast.LENGTH_SHORT).show();
                return;
            }

            // Send
            socketService.sendFile(fileName, mimeType, encrypted);
            addSystemMessage("📎 You shared: " + fileName);

        } catch (Exception e) {
            Log.e(TAG, "File sharing failed", e);
            Toast.makeText(this, "Failed to share file", Toast.LENGTH_SHORT).show();
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
            File file = new File(dir, "GhostLink_" + fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            return file;
        } catch (Exception e) {
            Log.e(TAG, "Save failed", e);
            return null;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Navigation
    // ═══════════════════════════════════════════════════════════════════════════

    private void confirmLeave() {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("Leave Room")
                .setMessage("Leave this room?")
                .setPositiveButton("Leave", (d, w) -> leaveRoom())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void leaveRoom() {
        SocketService.destroyInstance();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Don't destroy instance here — leaveRoom() handles it
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Adapters
    // ═══════════════════════════════════════════════════════════════════════════

    private class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {
        private final SimpleDateFormat timeFormat = new SimpleDateFormat("hh:mm a", Locale.getDefault());

        class ViewHolder extends RecyclerView.ViewHolder {
            View layoutReceived, layoutSent;
            TextView tvSenderName, tvMessageReceived, tvTimeReceived;
            TextView tvMessageSent, tvTimeSent;
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

            holder.layoutReceived.setVisibility(View.GONE);
            holder.layoutSent.setVisibility(View.GONE);
            holder.tvSystemMessage.setVisibility(View.GONE);

            if (msg.getType() == ChatMessage.TYPE_SYSTEM) {
                holder.tvSystemMessage.setVisibility(View.VISIBLE);
                holder.tvSystemMessage.setText(msg.getContent());
            } else if (msg.isMine()) {
                holder.layoutSent.setVisibility(View.VISIBLE);
                holder.tvMessageSent.setText(msg.getContent());
                holder.tvTimeSent.setText(time);
            } else {
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

            ViewHolder(View v) {
                super(v);
                tvUsername = v.findViewById(R.id.tv_username);
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
            String name = onlineUsers.get(position);
            holder.tvUsername.setText(name);
            // Highlight self
            if (name.equals(username)) {
                holder.tvUsername.setTextColor(ContextCompat.getColor(OnlineChatActivity.this, R.color.accent));
            } else {
                holder.tvUsername.setTextColor(ContextCompat.getColor(OnlineChatActivity.this, R.color.text_primary));
            }
        }

        @Override
        public int getItemCount() {
            return onlineUsers.size();
        }
    }
}

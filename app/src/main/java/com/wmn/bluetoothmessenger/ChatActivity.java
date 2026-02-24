package com.wmn.bluetoothmessenger;

import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmn.bluetoothmessenger.bluetooth.BluetoothService;
import com.wmn.bluetoothmessenger.manager.GroupManager;
import com.wmn.bluetoothmessenger.manager.MessageManager;
import com.wmn.bluetoothmessenger.manager.SessionManager;
import com.wmn.bluetoothmessenger.model.ChatMessage;
import com.wmn.bluetoothmessenger.util.Constants;

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

    private RecyclerView rvMessages;
    private EditText etMessage;
    private Button btnSend;
    private TextView tvGroupName, tvMemberCount, btnLeave, btnBack;

    // ── Fields ────────────────────────────────────────────────────────────────
    // (bluetoothService is obtained from the singleton; no local new BluetoothService())
    // ─────────────────────────────────────────────────────────────────────────
    private BluetoothService bluetoothService;
    private GroupManager groupManager;
    private MessageManager messageManager;
    private SessionManager sessionManager;

    private MessageAdapter messageAdapter;
    private final List<ChatMessage> displayMessages = new ArrayList<>();

    private String myDeviceName;
    private String groupName;
    private boolean isHost;

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
        rvMessages    = findViewById(R.id.rv_messages);
        etMessage     = findViewById(R.id.et_message);
        btnSend       = findViewById(R.id.btn_send);
        tvGroupName   = findViewById(R.id.tv_group_name);
        tvMemberCount = findViewById(R.id.tv_member_count);
        btnLeave      = findViewById(R.id.btn_leave);
        btnBack       = findViewById(R.id.btn_back);

        tvGroupName.setText(groupName);

        // Get device name – no BluetoothAdapter reference needed after this point
        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        try {
            myDeviceName = bluetoothAdapter.getName();
            if (myDeviceName == null) myDeviceName = "Me";
        } catch (SecurityException e) {
            myDeviceName = "Me";
        }

        // Setup RecyclerView
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        rvMessages.setLayoutManager(layoutManager);
        messageAdapter = new MessageAdapter();
        rvMessages.setAdapter(messageAdapter);

        // Setup managers
        setupGroupManager();
        setupMessageManager();
        setupSessionManager();
        setupBluetoothService();

        // Button listeners
        btnSend.setOnClickListener(v -> sendMessage());
        btnLeave.setOnClickListener(v -> confirmLeave());
        btnBack.setOnClickListener(v -> confirmLeave());

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
        if (isHost) {
            // BUG FIX: use the REAL passwordHash instead of hard-coding ""
            String passwordHash = getIntent().getStringExtra(Constants.EXTRA_PASSWORD_HASH);
            if (passwordHash == null) passwordHash = "";
            // GroupInfo stores the hash directly; pass the pre-hashed value
            // via setJoinedGroup-style approach so we don't re-hash it.
            // We create the group with the empty password slot and then fix it:
            groupManager.createGroupWithHash(groupName, passwordHash, myDeviceName);
        } else {
            groupManager.setJoinedGroup(groupName, "Host", myDeviceName);
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
        Handler btHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MSG_READ:
                        handleReceivedMessage((String) msg.obj);
                        break;

                    case Constants.MSG_CONNECTED:
                        // Only the HOST receives this (new member finished auth)
                        if (isHost) {
                            String deviceName = (String) msg.obj;
                            groupManager.addMember(deviceName);
                            addSystemMessage("📱 " + deviceName + " joined");
                            updateMemberCount();
                            sessionManager.resetActivity();
                        }
                        break;

                    case Constants.MSG_DISCONNECTED:
                        String leftDevice = (String) msg.obj;
                        groupManager.removeMember(leftDevice);
                        addSystemMessage("👋 " + leftDevice + " left");
                        updateMemberCount();
                        break;
                }
            }
        };

        // BUG FIX ①: attach to the LIVE singleton instead of creating a new instance
        bluetoothService = BluetoothService.getInstance();
        if (bluetoothService == null) {
            Toast.makeText(this, "Bluetooth session lost. Please restart.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        bluetoothService.setHandler(btHandler);

        // Seed the groupManager with members that were already connected before
        // this Activity started (e.g. members who joined during CreateGroupActivity).
        for (String name : bluetoothService.getConnectedDeviceNames()) {
            groupManager.addMember(name);
        }
        updateMemberCount();

        if (isHost) {
            String passwordHash = getIntent().getStringExtra(Constants.EXTRA_PASSWORD_HASH);
            bluetoothService.setPasswordHash(passwordHash);
            bluetoothService.setAuthCallback(new BluetoothService.AuthCallback() {
                @Override
                public boolean onAuthRequest(String receivedHash) {
                    return groupManager.authenticate(receivedHash);
                }

                @Override
                public void onAuthSuccess(String deviceName) {
                    // BUG FIX ③: MSG_CONNECTED already calls groupManager.addMember().
                    // Do NOT call it again here to prevent duplicate member entries.
                    sessionManager.resetActivity();
                }

                @Override
                public void onAuthFail(String deviceName) {
                    uiHandler.post(() -> addSystemMessage("🚫 Auth failed: " + deviceName));
                }
            });
            // BUG FIX ① continued: AcceptThread is ALREADY running in the singleton.
            // Do NOT call startHosting() again; doing so would reset pending connections.
        }
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

                ChatMessage msg = ChatMessage.createMessage(sender, content, false);
                messageManager.addMessage(msg);

                // If host, rebroadcast to all other clients
                if (isHost) {
                    bluetoothService.broadcastMessage(rawMessage);
                }
            }
        } else if (rawMessage.startsWith(Constants.PROTO_JOIN)) {
            // BUG FIX: Host learns of new members via MSG_CONNECTED (not via PROTO_JOIN broadcast).
            // Clients learn of OTHER members via PROTO_JOIN.  Also skip self-join notifications.
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
            groupManager.removeMember(deviceName);
            addSystemMessage("👋 " + deviceName + " left");
            updateMemberCount();
        } else if (rawMessage.equals(Constants.PROTO_SESSION_END)) {
            addSystemMessage("⏰ Group session ended by host");
            Toast.makeText(this, R.string.session_timeout, Toast.LENGTH_LONG).show();
            new Handler(Looper.getMainLooper()).postDelayed(this::leaveGroup, 2000);
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

    private void addSystemMessage(String text) {
        ChatMessage msg = ChatMessage.createSystemMessage(text);
        messageManager.addMessage(msg);
    }

    private void updateMemberCount() {
        int count = groupManager.getMemberCount();
        tvMemberCount.setText(count + (count == 1 ? " member" : " members"));
    }

    private void confirmLeave() {
        new AlertDialog.Builder(this)
                .setTitle("Leave Group")
                .setMessage("Are you sure you want to leave?")
                .setPositiveButton("Leave", (d, w) -> leaveGroup())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void leaveGroup() {
        // Notify peers we are leaving
        if (bluetoothService != null) {
            bluetoothService.broadcastMessage(Constants.PROTO_LEAVE + myDeviceName);
        }

        // Shut down managers
        messageManager.shutdown();
        sessionManager.shutdown();
        groupManager.clearGroup();

        // BUG FIX: fully destroy the singleton so connections don't linger
        BluetoothService.destroyInstance();

        // Return to main
        finish();
    }

    @Override
    public void onBackPressed() {
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
}

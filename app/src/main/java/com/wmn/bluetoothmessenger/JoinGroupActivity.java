package com.wmn.bluetoothmessenger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmn.bluetoothmessenger.bluetooth.BluetoothService;
import com.wmn.bluetoothmessenger.util.Constants;
import com.wmn.bluetoothmessenger.util.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for discovering nearby Bluetooth rooms and joining a group.
 *
 * Flow:
 * 1. User taps "Scan for Groups" → discovers nearby hosting devices.
 * 2. Discovered devices whose SDP name starts with "GhostLink_" are shown as
 * rooms.
 * 3. User taps a room → a password input section appears inline with the room
 * name.
 * 4. User enters the password and taps "Join Room".
 * 5. On success → navigates directly to ChatActivity.
 * 6. On failure → a persistent error message is shown (wrong password,
 * connection failed, etc.).
 */
public class JoinGroupActivity extends AppCompatActivity {

    private Button btnScan;
    private LinearLayout scanningLayout;
    private RecyclerView rvDevices;
    private TextView tvEmpty, tvStatus;

    // Join section views
    private LinearLayout joinSection;
    private TextView tvSelectedRoom, tvError;
    private EditText etJoinPassword;
    private Button btnJoinRoom;
    private ProgressBar progressJoin;

    private BluetoothAdapter bluetoothAdapter;
    private Handler handler;

    /** Hash sent to the host for auth; forwarded to ChatActivity. */
    private String passwordHash = "";

    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private DeviceAdapter deviceAdapter;

    /** The device the user has selected to join. */
    private BluetoothDevice selectedDevice;
    /** Index of the selected item in the adapter (-1 = none). */
    private int selectedPosition = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_group);

        btnScan = findViewById(R.id.btn_scan);
        scanningLayout = findViewById(R.id.scanning_layout);
        rvDevices = findViewById(R.id.rv_devices);
        tvEmpty = findViewById(R.id.tv_empty);
        tvStatus = findViewById(R.id.tv_status);

        // Join section
        joinSection = findViewById(R.id.join_section);
        tvSelectedRoom = findViewById(R.id.tv_selected_room);
        tvError = findViewById(R.id.tv_error);
        etJoinPassword = findViewById(R.id.et_join_password);
        btnJoinRoom = findViewById(R.id.btn_join_room);
        progressJoin = findViewById(R.id.progress_join);

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Setup RecyclerView
        deviceAdapter = new DeviceAdapter();
        rvDevices.setLayoutManager(new LinearLayoutManager(this));
        rvDevices.setAdapter(deviceAdapter);

        // Handler for Bluetooth events
        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MSG_CONNECTED:
                        tvStatus.setText(R.string.connected);
                        tvStatus.setVisibility(View.VISIBLE);
                        setJoinLoading(false);
                        navigateToChat((String) msg.obj);
                        break;

                    case Constants.MSG_CONNECTION_FAILED:
                        setJoinLoading(false);
                        String reason = (String) msg.obj;
                        showJoinError(reason);
                        break;
                }
            }
        };

        // Initialise singleton
        BluetoothService.init(bluetoothAdapter, handler);

        // Register discovery broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        btnScan.setOnClickListener(v -> startDiscovery());

        // Join button click
        btnJoinRoom.setOnClickListener(v -> attemptJoin());
    }

    // ========== Discovery ==========

    @SuppressWarnings("MissingPermission")
    private void startDiscovery() {
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
            return;
        }

        // Clear previous results and selection
        discoveredDevices.clear();
        deviceAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(View.GONE);
        rvDevices.setVisibility(View.VISIBLE);
        clearSelection();

        // Start scanning
        try {
            if (bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
            bluetoothAdapter.startDiscovery();

            btnScan.setEnabled(false);
            scanningLayout.setVisibility(View.VISIBLE);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
        }
    }

    // BroadcastReceiver for Bluetooth device discovery
    private final BroadcastReceiver discoveryReceiver = new BroadcastReceiver() {
        @SuppressWarnings("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    // Avoid duplicates
                    boolean exists = false;
                    for (BluetoothDevice d : discoveredDevices) {
                        if (d.getAddress().equals(device.getAddress())) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) {
                        discoveredDevices.add(device);
                        deviceAdapter.notifyItemInserted(discoveredDevices.size() - 1);
                        tvEmpty.setVisibility(View.GONE);
                    }
                }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                btnScan.setEnabled(true);
                scanningLayout.setVisibility(View.GONE);

                if (discoveredDevices.isEmpty()) {
                    tvEmpty.setVisibility(View.VISIBLE);
                    rvDevices.setVisibility(View.GONE);
                }
            }
        }
    };

    // ========== Room Selection ==========

    /**
     * Called when the user taps a discovered room in the list.
     */
    private void onRoomSelected(BluetoothDevice device, int position) {
        int oldPos = selectedPosition;
        selectedDevice = device;
        selectedPosition = position;

        // Update previous item
        if (oldPos >= 0 && oldPos < discoveredDevices.size()) {
            deviceAdapter.notifyItemChanged(oldPos);
        }
        // Update newly selected item
        deviceAdapter.notifyItemChanged(position);

        // Show the join section with room name
        String roomName = extractRoomName(device);
        tvSelectedRoom.setText("🔒 " + roomName);
        joinSection.setVisibility(View.VISIBLE);
        etJoinPassword.setText("");
        hideError();

        // Scroll the join section into view
        joinSection.post(() -> joinSection.requestFocus());
    }

    private void clearSelection() {
        int oldPos = selectedPosition;
        selectedDevice = null;
        selectedPosition = -1;
        if (oldPos >= 0) {
            deviceAdapter.notifyItemChanged(oldPos);
        }
        joinSection.setVisibility(View.GONE);
        etJoinPassword.setText("");
        hideError();
    }

    // ========== Join Attempt ==========

    private void attemptJoin() {
        if (selectedDevice == null) {
            showJoinError(getString(R.string.select_a_room));
            return;
        }

        String password = etJoinPassword.getText().toString().trim();
        if (password.isEmpty()) {
            showJoinError("Password is required");
            return;
        }

        hideError();
        setJoinLoading(true);
        connectToDevice(selectedDevice, password);
    }

    @SuppressWarnings("MissingPermission")
    private void connectToDevice(BluetoothDevice device, String password) {
        tvStatus.setText(R.string.joining_room);
        tvStatus.setVisibility(View.VISIBLE);

        // Store hash so ChatActivity can use it
        passwordHash = com.wmn.bluetoothmessenger.model.GroupInfo.hashPassword(password);

        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }

        BluetoothService.getInstance().connectToHost(device, password);
    }

    // ========== Error & Loading ==========

    private void showJoinError(String errorMessage) {
        tvError.setVisibility(View.VISIBLE);

        // Provide user-friendly error messages
        if (errorMessage != null && errorMessage.contains("Authentication failed")) {
            tvError.setText(R.string.wrong_password);
        } else if (errorMessage != null && errorMessage.contains("Permission denied")) {
            tvError.setText(R.string.permission_denied_msg);
        } else if (errorMessage != null) {
            // Show the raw error if it's already a user-friendly string, otherwise
            // wrap it in a generic connection-failed message
            if (errorMessage.length() > 80) {
                tvError.setText(R.string.connection_failed_msg);
            } else {
                tvError.setText(errorMessage);
            }
        } else {
            tvError.setText(R.string.connection_failed_msg);
        }

        tvStatus.setVisibility(View.GONE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }

    private void setJoinLoading(boolean loading) {
        btnJoinRoom.setEnabled(!loading);
        btnJoinRoom.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        progressJoin.setVisibility(loading ? View.VISIBLE : View.GONE);
        etJoinPassword.setEnabled(!loading);
        btnScan.setEnabled(!loading);
    }

    // ========== Navigation ==========

    @SuppressWarnings("MissingPermission")
    private void navigateToChat(String hostDeviceName) {
        String roomName = selectedDevice != null ? extractRoomName(selectedDevice) : hostDeviceName + "'s Group";
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_GROUP_NAME, roomName);
        intent.putExtra(Constants.EXTRA_PASSWORD_HASH, passwordHash);
        intent.putExtra(Constants.EXTRA_IS_HOST, false);
        startActivity(intent);
        finish();
    }

    // ========== Helpers ==========

    /**
     * Extract the room name from a Bluetooth device.
     * If the device name starts with "GhostLink_", strip the prefix to get the room
     * name.
     * Otherwise, fall back to the device name or MAC address.
     */
    @SuppressWarnings("MissingPermission")
    private String extractRoomName(BluetoothDevice device) {
        String name;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            name = null;
        }

        if (name != null && name.startsWith(Constants.BT_SERVICE_PREFIX)) {
            return name.substring(Constants.BT_SERVICE_PREFIX.length());
        }
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return device.getAddress();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(discoveryReceiver);
        } catch (Exception ignored) {
        }
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering())
                bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
        // Do NOT destroy singleton — the live connection must carry over to
        // ChatActivity
    }

    // ========== Room List Adapter ==========

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            View root;
            TextView tvRoomName, tvRoomHost, tvArrow;

            ViewHolder(View itemView) {
                super(itemView);
                root = itemView.findViewById(R.id.room_item_root);
                tvRoomName = itemView.findViewById(R.id.tv_room_name);
                tvRoomHost = itemView.findViewById(R.id.tv_room_host);
                tvArrow = itemView.findViewById(R.id.tv_room_arrow);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_room, parent, false);
            return new ViewHolder(view);
        }

        @SuppressWarnings("MissingPermission")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BluetoothDevice device = discoveredDevices.get(position);

            String roomName = extractRoomName(device);
            holder.tvRoomName.setText(roomName);

            // Show the device address or name as secondary info
            String hostInfo;
            try {
                String devName = device.getName();
                if (devName != null && devName.startsWith(Constants.BT_SERVICE_PREFIX)) {
                    hostInfo = "Hosted on " + device.getAddress();
                } else {
                    hostInfo = device.getAddress();
                }
            } catch (SecurityException e) {
                hostInfo = device.getAddress();
            }
            holder.tvRoomHost.setText(hostInfo);

            // Highlight if selected
            boolean isSelected = (position == selectedPosition);
            holder.root.setBackgroundResource(isSelected
                    ? R.drawable.item_room_selected_bg
                    : R.drawable.item_room_bg);
            holder.tvArrow.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            holder.itemView.setOnClickListener(v -> onRoomSelected(device, holder.getAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return discoveredDevices.size();
        }
    }
}

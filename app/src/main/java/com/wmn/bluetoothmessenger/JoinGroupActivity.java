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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.wmn.bluetoothmessenger.bluetooth.BluetoothService;
import com.wmn.bluetoothmessenger.util.Constants;
import com.wmn.bluetoothmessenger.util.PermissionHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Activity for discovering nearby Bluetooth devices and joining a group.
 * Uses Bluetooth Classic discovery to find available hosts.
 * Prompts for password and performs authentication handshake.
 */
public class JoinGroupActivity extends AppCompatActivity {

    private Button btnScan;
    private LinearLayout scanningLayout;
    private RecyclerView rvDevices;
    private TextView tvEmpty, tvStatus;

    private BluetoothAdapter bluetoothAdapter;
    private Handler handler;
    /** Hash sent to the host for auth; forwarded to ChatActivity so it can re-auth new members. */
    private String passwordHash = "";

    private final List<BluetoothDevice> discoveredDevices = new ArrayList<>();
    private DeviceAdapter deviceAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_join_group);

        btnScan = findViewById(R.id.btn_scan);
        scanningLayout = findViewById(R.id.scanning_layout);
        rvDevices = findViewById(R.id.rv_devices);
        tvEmpty = findViewById(R.id.tv_empty);
        tvStatus = findViewById(R.id.tv_status);

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
                        navigateToChat((String) msg.obj);
                        break;

                    case Constants.MSG_CONNECTION_FAILED:
                        tvStatus.setText("Connection failed: " + msg.obj);
                        tvStatus.setVisibility(View.VISIBLE);
                        Toast.makeText(JoinGroupActivity.this,
                                R.string.auth_failed, Toast.LENGTH_LONG).show();
                        break;
                }
            }
        };

        // Initialise singleton (no active connection yet on the join side)
        BluetoothService.init(bluetoothAdapter, handler);

        // Register discovery broadcast receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(discoveryReceiver, filter);

        btnScan.setOnClickListener(v -> startDiscovery());
    }

    @SuppressWarnings("MissingPermission")
    private void startDiscovery() {
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
            return;
        }

        // Clear previous results
        discoveredDevices.clear();
        deviceAdapter.notifyDataSetChanged();
        tvEmpty.setVisibility(View.GONE);
        rvDevices.setVisibility(View.VISIBLE);

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

    /**
     * Show password dialog and attempt connection.
     */
    private void showPasswordDialog(BluetoothDevice device) {
        EditText etPassword = new EditText(this);
        etPassword.setHint(R.string.enter_password);
        etPassword.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPassword.setPadding(48, 32, 48, 32);

        new AlertDialog.Builder(this, R.style.Theme_BluetoothMessenger)
                .setTitle("Join Group")
                .setMessage("Enter the group password")
                .setView(etPassword)
                .setPositiveButton("Connect", (dialog, which) -> {
                    String password = etPassword.getText().toString().trim();
                    if (password.isEmpty()) {
                        Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    connectToDevice(device, password);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @SuppressWarnings("MissingPermission")
    private void connectToDevice(BluetoothDevice device, String password) {
        tvStatus.setText(R.string.connecting);
        tvStatus.setVisibility(View.VISIBLE);

        // Store hash so ChatActivity can use it if it needs to re-auth new members as host
        passwordHash = com.wmn.bluetoothmessenger.model.GroupInfo.hashPassword(password);

        try {
            bluetoothAdapter.cancelDiscovery();
        } catch (SecurityException ignored) {
        }

        BluetoothService.getInstance().connectToHost(device, password);
    }

    @SuppressWarnings("MissingPermission")
    private void navigateToChat(String hostDeviceName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_GROUP_NAME,    hostDeviceName + "'s Group");
        intent.putExtra(Constants.EXTRA_PASSWORD_HASH, passwordHash);
        intent.putExtra(Constants.EXTRA_IS_HOST,       false);
        startActivity(intent);
        finish();   // live connection stays in singleton BluetoothService
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
        // Do NOT destroy singleton — the live connection must carry over to ChatActivity
    }

    // ========== Device List Adapter ==========

    private class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvName, tvAddress;

            ViewHolder(View itemView) {
                super(itemView);
                tvName = itemView.findViewById(android.R.id.text1);
                tvAddress = itemView.findViewById(android.R.id.text2);
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);

            // Style for dark theme
            TextView text1 = view.findViewById(android.R.id.text1);
            TextView text2 = view.findViewById(android.R.id.text2);
            text1.setTextColor(getResources().getColor(R.color.text_primary));
            text2.setTextColor(getResources().getColor(R.color.text_secondary));
            view.setBackgroundColor(getResources().getColor(R.color.surface));
            view.setPadding(32, 24, 32, 24);

            ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = 8;
            view.setLayoutParams(params);

            return new ViewHolder(view);
        }

        @SuppressWarnings("MissingPermission")
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BluetoothDevice device = discoveredDevices.get(position);

            String name;
            try {
                name = device.getName();
                if (name == null || name.isEmpty())
                    name = "Unknown Device";
            } catch (SecurityException e) {
                name = "Unknown Device";
            }

            holder.tvName.setText(name);
            holder.tvAddress.setText(device.getAddress());

            holder.itemView.setOnClickListener(v -> showPasswordDialog(device));
        }

        @Override
        public int getItemCount() {
            return discoveredDevices.size();
        }
    }
}

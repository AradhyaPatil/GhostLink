package com.wmn.bluetoothmessenger;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wmn.bluetoothmessenger.bluetooth.BluetoothService;
import com.wmn.bluetoothmessenger.manager.GroupManager;
import com.wmn.bluetoothmessenger.model.GroupInfo;
import com.wmn.bluetoothmessenger.util.Constants;
import com.wmn.bluetoothmessenger.util.PermissionHelper;

/**
 * Activity for creating and hosting a Bluetooth messaging group.
 * The host starts a server socket and waits for clients to connect.
 * Once a client connects, both navigate to the ChatActivity.
 */
public class CreateGroupActivity extends AppCompatActivity {

    private EditText etGroupName, etPassword;
    private Button btnCreate;
    private TextView tvStatus;
    private ProgressBar progressBar;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothService bluetoothService;
    private GroupManager groupManager;
    private Handler handler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_group);

        etGroupName = findViewById(R.id.et_group_name);
        etPassword = findViewById(R.id.et_password);
        btnCreate = findViewById(R.id.btn_create);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        groupManager = new GroupManager();

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case Constants.MSG_CONNECTED:
                        String deviceName = (String) msg.obj;
                        tvStatus.setText("✓ " + deviceName + " joined!");
                        tvStatus.setVisibility(View.VISIBLE);

                        // Navigate to chat
                        navigateToChat();
                        break;

                    case Constants.MSG_DISCONNECTED:
                        tvStatus.setText("Member disconnected: " + msg.obj);
                        break;
                }
            }
        };

        bluetoothService = new BluetoothService(bluetoothAdapter, handler);

        btnCreate.setOnClickListener(v -> createGroup());
    }

    @SuppressWarnings("MissingPermission")
    private void createGroup() {
        String groupName = etGroupName.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (groupName.isEmpty()) {
            etGroupName.setError("Enter a group name");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Enter a password");
            return;
        }
        if (password.length() < 4) {
            etPassword.setError("Password must be at least 4 characters");
            return;
        }

        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
            return;
        }

        // Get device name
        String deviceName;
        try {
            deviceName = bluetoothAdapter.getName();
            if (deviceName == null)
                deviceName = "Host";
        } catch (SecurityException e) {
            deviceName = "Host";
        }

        // Create the group
        groupManager.createGroup(groupName, password, deviceName);

        // Set up authentication on the service
        String passwordHash = GroupInfo.hashPassword(password);
        bluetoothService.setPasswordHash(passwordHash);
        bluetoothService.setAuthCallback(new BluetoothService.AuthCallback() {
            @Override
            public boolean onAuthRequest(String receivedHash) {
                return groupManager.authenticate(receivedHash);
            }

            @Override
            public void onAuthSuccess(String deviceName) {
                groupManager.addMember(deviceName);
            }

            @Override
            public void onAuthFail(String deviceName) {
                runOnUiThread(() -> Toast.makeText(CreateGroupActivity.this,
                        "Auth failed for: " + deviceName, Toast.LENGTH_SHORT).show());
            }
        });

        // Make device discoverable
        try {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                    Constants.DISCOVERABLE_DURATION);
            startActivityForResult(discoverableIntent, Constants.REQUEST_DISCOVERABLE);
        } catch (SecurityException e) {
            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
            return;
        }

        // Start hosting
        bluetoothService.startHosting();

        // Update UI
        btnCreate.setEnabled(false);
        tvStatus.setText(R.string.waiting_for_members);
        tvStatus.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void navigateToChat() {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_GROUP_NAME,
                groupManager.getCurrentGroup().getGroupName());
        intent.putExtra(Constants.EXTRA_PASSWORD_HASH,
                groupManager.getCurrentGroup().getPasswordHash());
        intent.putExtra(Constants.EXTRA_IS_HOST, true);
        startActivity(intent);
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Don't disconnect if navigating to chat - the service will be recreated there
    }
}

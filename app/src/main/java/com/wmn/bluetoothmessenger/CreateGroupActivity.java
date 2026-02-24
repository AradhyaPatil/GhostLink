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
 * The host stays on this screen while members join; tapping "Start Chat"
 * carries all live connections into ChatActivity via the singleton
 * BluetoothService.
 */
public class CreateGroupActivity extends AppCompatActivity {

    private EditText etGroupName, etPassword;
    private Button btnCreate, btnStartChat;
    private TextView tvStatus, tvMemberCount;
    private ProgressBar progressBar;

    private BluetoothAdapter bluetoothAdapter;
    private GroupManager groupManager;
    private Handler handler;

    /** The hash computed from the user-entered password; passed to ChatActivity. */
    private String passwordHash = "";
    /** Number of clients that have successfully authenticated. */
    private int joinedCount = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_group);

        etGroupName   = findViewById(R.id.et_group_name);
        etPassword    = findViewById(R.id.et_password);
        btnCreate     = findViewById(R.id.btn_create);
        tvStatus      = findViewById(R.id.tv_status);
        progressBar   = findViewById(R.id.progress_bar);
        btnStartChat  = findViewById(R.id.btn_start_chat);
        tvMemberCount = findViewById(R.id.tv_member_count_create);

        TextView btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        groupManager = new GroupManager();

        handler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case Constants.MSG_CONNECTED:
                        // A new member authenticated and connected
                        String deviceName = (String) msg.obj;
                        joinedCount++;
                        tvStatus.setText("✓ " + deviceName + " joined!");
                        tvStatus.setVisibility(View.VISIBLE);
                        tvMemberCount.setText(joinedCount + (joinedCount == 1 ? " member waiting" : " members waiting"));
                        tvMemberCount.setVisibility(View.VISIBLE);
                        // Reveal "Start Chat" button on first join
                        btnStartChat.setVisibility(View.VISIBLE);
                        break;

                    case Constants.MSG_DISCONNECTED:
                        tvStatus.setText("Member disconnected: " + msg.obj);
                        break;
                }
            }
        };

        // Initialise the singleton BluetoothService early so its Handler is set
        BluetoothService.init(bluetoothAdapter, handler);

        btnCreate.setOnClickListener(v -> createGroup());
        btnStartChat.setOnClickListener(v -> navigateToChat());
    }

    @SuppressWarnings("MissingPermission")
    private void createGroup() {
        String groupName = etGroupName.getText().toString().trim();
        String password  = etPassword.getText().toString().trim();

        if (groupName.isEmpty()) { etGroupName.setError("Enter a group name"); return; }
        if (password.isEmpty())  { etPassword.setError("Enter a password");    return; }
        if (password.length() < 4) { etPassword.setError("Password must be at least 4 characters"); return; }

        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
            return;
        }

        // Get device name
        String deviceName;
        try {
            deviceName = bluetoothAdapter.getName();
            if (deviceName == null) deviceName = "Host";
        } catch (SecurityException e) {
            deviceName = "Host";
        }

        // Create the group and compute hash
        groupManager.createGroup(groupName, password, deviceName);
        passwordHash = GroupInfo.hashPassword(password);

        // Configure the singleton service with auth details
        BluetoothService svc = BluetoothService.getInstance();
        svc.setPasswordHash(passwordHash);
        svc.setAuthCallback(new BluetoothService.AuthCallback() {
            @Override
            public boolean onAuthRequest(String receivedHash) {
                return groupManager.authenticate(receivedHash);
            }

            @Override
            public void onAuthSuccess(String name) {
                groupManager.addMember(name);
                // MSG_CONNECTED is also fired; member-count update happens there
            }

            @Override
            public void onAuthFail(String name) {
                runOnUiThread(() -> Toast.makeText(CreateGroupActivity.this,
                        "Auth failed for: " + name, Toast.LENGTH_SHORT).show());
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
        svc.startHosting();

        // Update UI
        btnCreate.setEnabled(false);
        etGroupName.setEnabled(false);
        etPassword.setEnabled(false);
        tvStatus.setText(R.string.waiting_for_members);
        tvStatus.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.VISIBLE);
    }

    private void navigateToChat() {
        if (groupManager.getCurrentGroup() == null) return;
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra(Constants.EXTRA_GROUP_NAME,    groupManager.getCurrentGroup().getGroupName());
        intent.putExtra(Constants.EXTRA_PASSWORD_HASH, passwordHash);
        intent.putExtra(Constants.EXTRA_IS_HOST,       true);
        startActivity(intent);
        finish();   // CreateGroupActivity is done; live connections stay in singleton
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Do NOT destroy the singleton – live connections must survive the transition
    }
}

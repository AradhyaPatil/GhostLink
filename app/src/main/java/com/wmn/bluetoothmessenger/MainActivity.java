package com.wmn.bluetoothmessenger;

import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.wmn.bluetoothmessenger.util.Constants;
import com.wmn.bluetoothmessenger.util.PermissionHelper;

/**
 * Main launcher activity.
 * Provides options to Create or Join a Bluetooth messaging group.
 * Checks for Bluetooth availability and requests permissions.
 */
public class MainActivity extends AppCompatActivity {

    private BluetoothAdapter bluetoothAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(this, R.string.bt_not_supported, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Request permissions
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
        }

        Button btnCreate = findViewById(R.id.btn_create_group);
        Button btnJoin = findViewById(R.id.btn_join_group);

        btnCreate.setOnClickListener(v -> {
            if (checkBluetoothReady()) {
                startActivity(new Intent(this, CreateGroupActivity.class));
            }
        });

        btnJoin.setOnClickListener(v -> {
            if (checkBluetoothReady()) {
                startActivity(new Intent(this, JoinGroupActivity.class));
            }
        });
    }

    @SuppressWarnings("MissingPermission")
    private boolean checkBluetoothReady() {
        if (!PermissionHelper.hasBluetoothPermissions(this)) {
            PermissionHelper.requestBluetoothPermissions(this);
            return false;
        }

        if (!bluetoothAdapter.isEnabled()) {
            try {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, Constants.REQUEST_ENABLE_BT);
            } catch (SecurityException e) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_SHORT).show();
            }
            return false;
        }

        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Constants.REQUEST_PERMISSIONS) {
            if (!PermissionHelper.allPermissionsGranted(grantResults)) {
                Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
            }
        }
    }
}

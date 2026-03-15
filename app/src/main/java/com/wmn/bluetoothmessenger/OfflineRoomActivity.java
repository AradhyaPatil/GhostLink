package com.wmn.bluetoothmessenger;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

import com.wmn.bluetoothmessenger.util.Constants;

/**
 * Offline room setup screen for Bluetooth chat.
 * Users enter username, room name and password once, then choose Create/Join.
 */
public class OfflineRoomActivity extends AppCompatActivity {

    private EditText etUsername;
    private EditText etRoomName;
    private EditText etPassword;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_offline_room);

        etUsername = findViewById(R.id.et_username);
        etRoomName = findViewById(R.id.et_room_name);
        etPassword = findViewById(R.id.et_password);

        Button btnCreate = findViewById(R.id.btn_create_room);
        Button btnJoin = findViewById(R.id.btn_join_room);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        btnCreate.setOnClickListener(v -> proceedToCreate());
        btnJoin.setOnClickListener(v -> proceedToJoin());
    }

    private boolean validateInputs() {
        String username = etUsername.getText().toString().trim();
        String roomName = etRoomName.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (username.isEmpty()) {
            etUsername.setError("Required");
            return false;
        }
        if (roomName.isEmpty()) {
            etRoomName.setError("Required");
            return false;
        }
        if (password.length() < 4) {
            etPassword.setError("Minimum 4 characters");
            return false;
        }
        return true;
    }

    private void proceedToCreate() {
        if (!validateInputs()) {
            return;
        }

        Intent intent = new Intent(this, CreateGroupActivity.class);
        intent.putExtra(Constants.EXTRA_USERNAME, etUsername.getText().toString().trim());
        intent.putExtra(Constants.EXTRA_GROUP_NAME, etRoomName.getText().toString().trim());
        intent.putExtra(Constants.EXTRA_PASSWORD, etPassword.getText().toString().trim());
        startActivity(intent);
    }

    private void proceedToJoin() {
        if (!validateInputs()) {
            return;
        }

        Intent intent = new Intent(this, JoinGroupActivity.class);
        intent.putExtra(Constants.EXTRA_USERNAME, etUsername.getText().toString().trim());
        intent.putExtra(Constants.EXTRA_GROUP_NAME, etRoomName.getText().toString().trim());
        intent.putExtra(Constants.EXTRA_PASSWORD, etPassword.getText().toString().trim());
        startActivity(intent);
    }
}

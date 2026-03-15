package com.wmn.bluetoothmessenger;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.wmn.bluetoothmessenger.model.GroupInfo;
import com.wmn.bluetoothmessenger.network.SocketService;
import com.wmn.bluetoothmessenger.util.Constants;

/**
 * Screen where users enter room name, password, and username to join
 * an online (WiFi) chat room via Socket.IO.
 */
public class OnlineRoomActivity extends AppCompatActivity {

    private EditText etUsername, etRoomName, etPassword;
    private Button btnConnect;
    private TextView tvStatus;
    private ProgressBar progressBar;

    private static final String DEFAULT_SERVER = SocketService.DEFAULT_SERVER_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_room);

        etUsername = findViewById(R.id.et_username);
        etRoomName = findViewById(R.id.et_room_name);
        etPassword = findViewById(R.id.et_password);
        btnConnect = findViewById(R.id.btn_connect);
        tvStatus = findViewById(R.id.tv_status);
        progressBar = findViewById(R.id.progress_bar);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());
        btnConnect.setOnClickListener(v -> attemptConnect());
    }

    private void attemptConnect() {
        String username = etUsername.getText().toString().trim();
        String roomName = etRoomName.getText().toString().trim();
        String password = etPassword.getText().toString().trim();

        if (username.isEmpty()) {
            etUsername.setError("Required");
            return;
        }
        if (roomName.isEmpty()) {
            etRoomName.setError("Required");
            return;
        }
        if (password.isEmpty()) {
            etPassword.setError("Required");
            return;
        }
        // Show loading
        btnConnect.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.GONE);

        String passwordHash = GroupInfo.hashPassword(password);

        SocketService socketService = SocketService.getInstance();

        // Set up handler to catch connection result
        final String finalServerUrl = DEFAULT_SERVER;
        final String finalUsername = username;
        final String finalRoomName = roomName;
        final String finalPasswordHash = passwordHash;

        socketService.setHandler(new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MSG_CONNECTED:
                        // Success — navigate to chat
                        progressBar.setVisibility(View.GONE);

                        Intent intent = new Intent(OnlineRoomActivity.this, OnlineChatActivity.class);
                        intent.putExtra("room_name", finalRoomName);
                        intent.putExtra("username", socketService.getCurrentUsername());
                        intent.putExtra("password", finalPasswordHash);
                        startActivity(intent);
                        finish();
                        break;

                    case Constants.MSG_CONNECTION_FAILED:
                        progressBar.setVisibility(View.GONE);
                        btnConnect.setEnabled(true);
                        String error = (String) msg.obj;
                        tvStatus.setText(error != null ? error : "Connection failed");
                        tvStatus.setVisibility(View.VISIBLE);
                        break;
                }
            }
        });

        // Queue credentials so EVENT_CONNECT inside SocketService will
        // call joinRoom the instant the socket becomes connected.
        socketService.setPendingJoin(finalRoomName, finalPasswordHash, finalUsername);
        socketService.connect();

        // Show a "waking up" hint after 6 seconds (Render free tier cold-starts
        // can take 30-60 s) and declare failure after 60 seconds.
        final Handler waitHandler = new Handler(Looper.getMainLooper());

        Runnable wakeHint = () -> {
            if (progressBar.getVisibility() == View.VISIBLE) {
                tvStatus.setText("Server is waking up, please wait…");
                tvStatus.setVisibility(View.VISIBLE);
            }
        };

        Runnable timeout = () -> {
            if (progressBar.getVisibility() == View.VISIBLE) {
                progressBar.setVisibility(View.GONE);
                btnConnect.setEnabled(true);
                tvStatus.setText("Cannot reach server. Check your internet connection and try again.");
                tvStatus.setVisibility(View.VISIBLE);
                socketService.setPendingJoin(null, null, null); // cancel queued join
            }
        };

        waitHandler.postDelayed(wakeHint, 6_000);
        waitHandler.postDelayed(timeout, 60_000);
    }
}

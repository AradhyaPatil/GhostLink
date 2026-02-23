package com.wmn.bluetoothmessenger.util;

import java.util.UUID;

/**
 * Application-wide constants for Bluetooth communication, timeouts, and
 * protocol tags.
 */
public final class Constants {

    private Constants() {
    } // Prevent instantiation

    // Bluetooth
    public static final String BT_SERVICE_NAME = "BluetoothMessenger";
    public static final UUID BT_UUID = UUID.fromString("fa87c0d0-afac-11de-8a39-0800200c9a66");

    // Protocol message prefixes
    public static final String PROTO_AUTH = "AUTH:";
    public static final String PROTO_AUTH_OK = "AUTH_OK";
    public static final String PROTO_AUTH_FAIL = "AUTH_FAIL";
    public static final String PROTO_MSG = "MSG:";
    public static final String PROTO_JOIN = "JOIN:";
    public static final String PROTO_LEAVE = "LEAVE:";
    public static final String PROTO_SESSION_END = "SESSION_END";

    // Timeouts
    public static final long MESSAGE_TTL_MS = 60 * 1000; // 1 minute
    public static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000; // 30 minutes
    public static final long TTL_CHECK_INTERVAL_MS = 5 * 1000; // Check TTL every 5 seconds
    public static final long SESSION_CHECK_INTERVAL_MS = 30 * 1000; // Check session every 30 seconds

    // Handler message types
    public static final int MSG_READ = 1;
    public static final int MSG_WRITE = 2;
    public static final int MSG_CONNECTED = 3;
    public static final int MSG_DISCONNECTED = 4;
    public static final int MSG_CONNECTION_FAILED = 5;
    public static final int MSG_TOAST = 6;

    // Intent extras
    public static final String EXTRA_GROUP_NAME = "group_name";
    public static final String EXTRA_PASSWORD_HASH = "password_hash";
    public static final String EXTRA_IS_HOST = "is_host";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";

    // Discoverable duration
    public static final int DISCOVERABLE_DURATION = 300; // 5 minutes

    // Permission request codes
    public static final int REQUEST_ENABLE_BT = 1001;
    public static final int REQUEST_DISCOVERABLE = 1002;
    public static final int REQUEST_PERMISSIONS = 1003;
}

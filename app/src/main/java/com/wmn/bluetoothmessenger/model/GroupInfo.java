package com.wmn.bluetoothmessenger.model;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Holds metadata about a Bluetooth messaging group.
 */
public class GroupInfo {

    private final String groupName;
    private final String passwordHash;
    private final String hostDeviceName;
    private final long creationTime;

    public GroupInfo(String groupName, String password, String hostDeviceName) {
        this.groupName = groupName;
        this.passwordHash = hashPassword(password);
        this.hostDeviceName = hostDeviceName;
        this.creationTime = System.currentTimeMillis();
    }

    public String getGroupName() {
        return groupName;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getHostDeviceName() {
        return hostDeviceName;
    }

    public long getCreationTime() {
        return creationTime;
    }

    /**
     * Verifies a password against the stored hash.
     */
    public boolean verifyPassword(String password) {
        return passwordHash.equals(hashPassword(password));
    }

    /**
     * Verifies a pre-computed hash against the stored hash.
     */
    public boolean verifyHash(String hash) {
        return passwordHash.equals(hash);
    }

    /**
     * Computes SHA-256 hash of a password string.
     */
    public static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(password.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on Android
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}

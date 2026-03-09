package com.wmn.bluetoothmessenger.util;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption using a password-derived key (PBKDF2).
 * All messages are encrypted client-side before sending to the server.
 */
public class CryptoUtil {

    private static final String TAG = "CryptoUtil";
    private static final int GCM_IV_LENGTH = 12; // 96-bit IV
    private static final int GCM_TAG_LENGTH = 128; // 128-bit auth tag
    private static final int KEY_LENGTH = 256; // AES-256
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final byte[] SALT = "GhostLinkSalt2024".getBytes(StandardCharsets.UTF_8);

    /**
     * Derive an AES-256 key from a password using PBKDF2.
     */
    public static SecretKey deriveKey(String password) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), SALT, PBKDF2_ITERATIONS, KEY_LENGTH);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            return new SecretKeySpec(keyBytes, "AES");
        } catch (Exception e) {
            Log.e(TAG, "Key derivation failed", e);
            // Fallback: simple SHA-256 hash as key
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(hash, "AES");
            } catch (Exception ex) {
                throw new RuntimeException("Cannot derive key", ex);
            }
        }
    }

    /**
     * Encrypt plaintext with AES-256-GCM.
     * Returns Base64-encoded string: IV (12 bytes) + ciphertext + tag.
     */
    public static String encrypt(String plaintext, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Prepend IV to ciphertext
            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypt a Base64-encoded AES-256-GCM ciphertext.
     */
    public static String decrypt(String ciphertext, SecretKey key) {
        try {
            byte[] combined = Base64.decode(ciphertext, Base64.NO_WRAP);

            // Extract IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);

            // Extract encrypted data
            byte[] encrypted = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed", e);
            return null;
        }
    }

    /**
     * Encrypt raw bytes (for file sharing).
     */
    public static String encryptBytes(byte[] data, SecretKey key) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] encrypted = cipher.doFinal(data);

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Byte encryption failed", e);
            return null;
        }
    }

    /**
     * Decrypt to raw bytes (for file sharing).
     */
    public static byte[] decryptBytes(String ciphertext, SecretKey key) {
        try {
            byte[] combined = Base64.decode(ciphertext, Base64.NO_WRAP);

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, iv.length);

            byte[] encrypted = new byte[combined.length - iv.length];
            System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            Log.e(TAG, "Byte decryption failed", e);
            return null;
        }
    }
}

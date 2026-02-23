package com.wmn.bluetoothmessenger.model;

/**
 * Represents a chat message with TTL support.
 * Messages are held in-memory only — no persistent storage.
 */
public class ChatMessage {

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_SYSTEM = 1;

    private final String senderName;
    private final String content;
    private final long timestamp;
    private final boolean isMine;
    private final int type;

    public ChatMessage(String senderName, String content, long timestamp, boolean isMine, int type) {
        this.senderName = senderName;
        this.content = content;
        this.timestamp = timestamp;
        this.isMine = isMine;
        this.type = type;
    }

    /** Creates a normal user message. */
    public static ChatMessage createMessage(String sender, String content, boolean isMine) {
        return new ChatMessage(sender, content, System.currentTimeMillis(), isMine, TYPE_NORMAL);
    }

    /** Creates a system notification message (join/leave/timeout). */
    public static ChatMessage createSystemMessage(String content) {
        return new ChatMessage("System", content, System.currentTimeMillis(), false, TYPE_SYSTEM);
    }

    public String getSenderName() {
        return senderName;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isMine() {
        return isMine;
    }

    public int getType() {
        return type;
    }

    /** Checks if this message has exceeded its TTL (1 minute). */
    public boolean isExpired() {
        return (System.currentTimeMillis() - timestamp) > com.wmn.bluetoothmessenger.util.Constants.MESSAGE_TTL_MS;
    }
}

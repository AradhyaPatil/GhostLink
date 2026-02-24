package com.wmn.bluetoothmessenger.manager;

import com.wmn.bluetoothmessenger.model.GroupInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages group lifecycle, member tracking, and authentication.
 * Supports creating groups with password protection and authenticating incoming
 * connections.
 */
public class GroupManager {

    private GroupInfo currentGroup;
    private final List<String> members = Collections.synchronizedList(new ArrayList<>());

    /**
     * Create a new group.
     */
    public void createGroup(String groupName, String password, String hostDeviceName) {
        currentGroup = new GroupInfo(groupName, password, hostDeviceName);
        members.clear();
        members.add(hostDeviceName); // Host is the first member
    }

    /**
     * Create a group using a pre-computed password hash so the hash is not
     * hashed a second time.  Used by ChatActivity when the host transitions from
     * CreateGroupActivity and the hash is already available via the Intent.
     */
    public void createGroupWithHash(String groupName, String passwordHash, String hostDeviceName) {
        currentGroup = GroupInfo.withHash(groupName, passwordHash, hostDeviceName);
        members.clear();
        members.add(hostDeviceName);
    }

    /**
     * Authenticate a joining user by comparing password hashes.
     */
    public boolean authenticate(String passwordHash) {
        if (currentGroup == null)
            return false;
        return currentGroup.verifyHash(passwordHash);
    }

    /**
     * Add a member to the group.
     */
    public void addMember(String deviceName) {
        if (!members.contains(deviceName)) {
            members.add(deviceName);
        }
    }

    /**
     * Remove a member from the group.
     */
    public void removeMember(String deviceName) {
        members.remove(deviceName);
    }

    /**
     * Get all current members.
     */
    public List<String> getMembers() {
        return new ArrayList<>(members);
    }

    /**
     * Get member count.
     */
    public int getMemberCount() {
        return members.size();
    }

    /**
     * Get the current group info.
     */
    public GroupInfo getCurrentGroup() {
        return currentGroup;
    }

    /**
     * Set group info when joining as client.
     */
    public void setJoinedGroup(String groupName, String hostDeviceName, String myDeviceName) {
        currentGroup = new GroupInfo(groupName, "", hostDeviceName);
        members.clear();
        members.add(hostDeviceName);
        members.add(myDeviceName);
    }

    /**
     * Clear all group data.
     */
    public void clearGroup() {
        currentGroup = null;
        members.clear();
    }
}

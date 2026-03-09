/**
 * GhostLink — Socket.IO Server
 *
 * Handles:
 *   - Room creation / joining with password-hash verification
 *   - Real-time message relay (all messages are E2E encrypted on client)
 *   - File/document sharing relay
 *   - User presence (join / leave / user list)
 */

const express = require("express");
const http = require("http");
const { Server } = require("socket.io");

const app = express();
const server = http.createServer(app);
const io = new Server(server, {
    cors: { origin: "*" },
    maxHttpBufferSize: 10 * 1024 * 1024, // 10 MB for file transfers
});

// ─── In-memory room store ────────────────────────────────────────────────────
// rooms = { roomName: { passwordHash, users: { socketId: username } } }
const rooms = {};

// Health check
app.get("/", (_req, res) => {
    res.json({
        status: "ok",
        rooms: Object.keys(rooms).length,
        uptime: process.uptime(),
    });
});

// ─── Socket.IO ───────────────────────────────────────────────────────────────
io.on("connection", (socket) => {
    console.log(`⚡ Connected: ${socket.id}`);

    let currentRoom = null;
    let currentUsername = null;

    // ── Join room ──────────────────────────────────────────────────────────────
    socket.on("joinRoom", ({ roomName, passwordHash, username }) => {
        if (!roomName || !username) {
            socket.emit("authError", "Room name and username are required.");
            return;
        }

        // Create room if it doesn't exist
        if (!rooms[roomName]) {
            rooms[roomName] = { passwordHash: passwordHash || "", users: {} };
            console.log(`🏠 Room created: "${roomName}"`);
        }

        // Verify password
        const room = rooms[roomName];
        if (room.passwordHash && room.passwordHash !== passwordHash) {
            socket.emit("authError", "Wrong password.");
            return;
        }

        // Check for duplicate username in the room
        const existingNames = Object.values(room.users);
        let finalName = username;
        if (existingNames.includes(username)) {
            finalName = username + "_" + Math.floor(Math.random() * 100);
        }

        // Join
        socket.join(roomName);
        room.users[socket.id] = finalName;
        currentRoom = roomName;
        currentUsername = finalName;

        console.log(`👤 ${finalName} joined "${roomName}" (${Object.keys(room.users).length} users)`);

        // Confirm join to the user
        socket.emit("joinedRoom", {
            roomName,
            username: finalName,
            users: Object.values(room.users),
        });

        // Notify others
        socket.to(roomName).emit("userJoined", {
            username: finalName,
            users: Object.values(room.users),
        });
    });

    // ── Chat message (encrypted on client side) ───────────────────────────────
    socket.on("chatMessage", ({ roomName, encryptedMessage, senderName }) => {
        if (!roomName || !rooms[roomName]) return;

        socket.to(roomName).emit("chatMessage", {
            encryptedMessage,
            senderName: senderName || currentUsername,
            timestamp: Date.now(),
        });
    });

    // ── File sharing (base64, encrypted on client) ────────────────────────────
    socket.on("shareFile", ({ roomName, fileName, mimeType, fileData, senderName }) => {
        if (!roomName || !rooms[roomName]) return;

        console.log(`📎 ${senderName || currentUsername} shared "${fileName}" in "${roomName}" (${Math.round(fileData.length / 1024)}KB)`);

        socket.to(roomName).emit("fileShared", {
            fileName,
            mimeType,
            fileData,      // base64-encoded (encrypted on client)
            senderName: senderName || currentUsername,
            timestamp: Date.now(),
        });
    });

    // ── Leave room ─────────────────────────────────────────────────────────────
    socket.on("leaveRoom", () => {
        handleLeave(socket);
    });

    // ── Disconnect ─────────────────────────────────────────────────────────────
    socket.on("disconnect", () => {
        handleLeave(socket);
        console.log(`💤 Disconnected: ${socket.id}`);
    });

    function handleLeave(sock) {
        if (!currentRoom || !rooms[currentRoom]) return;

        const room = rooms[currentRoom];
        const leavingUser = room.users[sock.id] || currentUsername;
        delete room.users[sock.id];

        // Notify remaining users
        sock.to(currentRoom).emit("userLeft", {
            username: leavingUser,
            users: Object.values(room.users),
        });

        sock.leave(currentRoom);
        console.log(`👋 ${leavingUser} left "${currentRoom}" (${Object.keys(room.users).length} remaining)`);

        // Clean up empty rooms
        if (Object.keys(room.users).length === 0) {
            delete rooms[currentRoom];
            console.log(`🗑️  Room "${currentRoom}" deleted (empty)`);
        }

        currentRoom = null;
        currentUsername = null;
    }
});

// ─── Start ───────────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 3000;
server.listen(PORT, "0.0.0.0", () => {
    console.log(`\n🚀 GhostLink server running on port ${PORT}`);
    console.log(`   http://localhost:${PORT}\n`);
});

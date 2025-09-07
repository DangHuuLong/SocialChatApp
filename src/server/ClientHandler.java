package server;

import server.dao.MessageDao;
import server.dao.RoomDao;
import common.Frame;
import common.MessageType;

import java.io.*;
import java.net.Socket;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Set<ClientHandler> clients;
    private final Map<String, ClientHandler> online;
    private final MessageDao messageDao;
    private final RoomDao roomDao;
    private final RoomRegistry rooms;

    private BufferedReader in;
    private PrintWriter out;
    private String username = null;

    public ClientHandler(Socket socket,
                         Set<ClientHandler> clients,
                         Map<String, ClientHandler> online,
                         MessageDao messageDao,
                         RoomDao roomDao,
                         RoomRegistry roomRegistry) {
        this.socket = socket;
        this.clients = clients;
        this.online  = online;
        this.messageDao = messageDao;
        this.roomDao = roomDao;
        this.rooms = roomRegistry;
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            send("HELLO");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toUpperCase();
                String arg = (parts.length > 1) ? parts[1] : "";

                switch (cmd) {
                    case "LOGIN" -> handleLogin(arg);

                    case "MSG" -> {
                        if (!ensureLoggedIn()) break;
                        broadcast(username + ": " + arg, false);
                        send("OK SENT");
                    }

                    case "DM" -> {
                        if (!ensureLoggedIn()) break;
                        String[] dm = arg.split("\\s+", 2);
                        if (dm.length < 2) { send("ERR BAD_DM"); break; }
                        String to  = dm[0].trim();
                        String msgBody = dm[1];

                        Frame f = new Frame(MessageType.DM, username, to, msgBody);
                        ClientHandler target = online.get(to);
                        if (target != null) {
                            target.send("[DM] " + username + ": " + msgBody);
                            send("OK DM");
                            try { messageDao.saveSent(f); } catch (SQLException e) { e.printStackTrace(); }
                        } else {
                            try {
                                messageDao.saveQueued(f);
                                send("OK QUEUED");
                            } catch (SQLException e) {
                                send("ERR QUEUE_FAIL");
                                e.printStackTrace();
                            }
                        }
                    }

                    case "HISTORY" -> {
                        if (!ensureLoggedIn()) break;
                        String[] hp = arg.split("\\s+", 2);
                        String peer = hp.length >= 1 ? hp[0].trim() : "";
                        if (peer.isEmpty()) { send("ERR BAD_HISTORY"); break; }
                        int limit = 50;
                        if (hp.length == 2) {
                            try { limit = Math.max(1, Integer.parseInt(hp[1])); } catch (Exception ignore) {}
                        }
                        try {
                            var rows = messageDao.loadConversation(username, peer, limit);
                            for (var r : rows) {
                                boolean incoming = !r.sender.equals(username);
                                if (incoming) send("[HIST IN] " + r.sender + ": " + r.body);
                                else          send("[HIST OUT] " + r.body);
                            }
                            send("OK HISTORY");
                        } catch (SQLException e) {
                            send("ERR HISTORY_FAIL");
                            e.printStackTrace();
                        }
                    }

                    case "WHO" -> send("ONLINE " + String.join(",", online.keySet()));

                    // ====== ROOM commands ======
                    case "ROOM.CREATE" -> cmdRoomCreate(arg);   // Messenger-like: ROOM.CREATE <name> <user1> [user2 ...]
                    case "ROOM.JOIN"   -> cmdRoomJoin(arg);     // ROOM.JOIN <name>
                    case "ROOM.LEAVE"  -> cmdRoomLeave(arg);    // ROOM.LEAVE <name>
                    case "ROOM.ADD"    -> cmdRoomAdd(arg);      // ROOM.ADD <name> <user>  (owner-only)
                    case "ROOM.MSG"    -> cmdRoomMsg(arg);      // ROOM.MSG <name> <text>
                    case "ROOM.MEMBERS"-> cmdRoomMembers(arg);  // ROOM.MEMBERS <name>

                    case "QUIT" -> { send("BYE"); return; }

                    default -> send("ERR UNKNOWN_CMD");
                }
            }
        } catch (IOException ignored) {
        } finally {
            cleanup();
        }
    }

    // ====== ROOM handlers ======

    private void cmdRoomCreate(String arg){
        if (!ensureLoggedIn()) return;
        String[] p = arg.split("\\s+");
        if (p.length < 2) {
            send("ERR ROOM_CREATE usage: ROOM.CREATE <roomName> <user1> [user2 ...]");
            return;
        }
        String roomName = p[0];
        var initial = new java.util.LinkedHashSet<String>();
        for (int i = 1; i < p.length; i++) {
            String u = p[i].replace(",", "").replace("@", "").trim();
            if (!u.isEmpty()) initial.add(u);
        }
        try {
            long roomId = roomDao.createRoomWithMembers(roomName, username, initial);
            rooms.cache(roomName, roomId);
            rooms.join(roomId, this);
            // auto-join runtime cho ai đang online
            for (String u : initial) {
                ClientHandler t = online.get(u);
                if (t != null) {
                    rooms.join(roomId, t);
                    t.send("[ROOM] You were added to " + roomName);
                }
            }
            send("OK ROOM_CREATE " + roomName + " " + roomId);
        } catch (IllegalArgumentException iae) {
            if ("ROOM_NEEDS_3".equals(iae.getMessage())) send("ERR ROOM_NEEDS_3");
            else send("ERR ROOM_CREATE_FAILED");
        } catch (SQLException sqle) {
            if ("ROOM_EXISTS".equals(sqle.getMessage())) send("ERR ROOM_EXISTS");
            else send("ERR ROOM_CREATE_FAILED");
        } catch (Exception e) {
            send("ERR ROOM_CREATE_FAILED");
        }
    }

    private void cmdRoomJoin(String arg){
        if (!ensureLoggedIn()) return;
        String roomName = arg.trim();
        if (roomName.isEmpty()) { send("ERR ROOM_JOIN name_required"); return; }
        try {
            Long roomId = rooms.cachedId(roomName);
            if (roomId == null) roomId = roomDao.findRoomIdByName(roomName);
            if (roomId == null) { send("ERR ROOM_NOT_FOUND"); return; }
            roomDao.addMember(roomId, username); // hoặc kiểm tra policy
            rooms.cache(roomName, roomId);
            rooms.join(roomId, this);
            send("OK ROOM_JOIN " + roomName + " " + roomId);
        } catch (Exception e){ send("ERR ROOM_JOIN_FAILED"); }
    }

    private void cmdRoomLeave(String arg){
        if (!ensureLoggedIn()) return;
        String roomName = arg.trim();
        if (roomName.isEmpty()) { send("ERR ROOM_LEAVE name_required"); return; }
        try {
            Long roomId = roomDao.findRoomIdByName(roomName);
            if (roomId == null) { send("ERR ROOM_NOT_FOUND"); return; }
            if (!roomDao.isMember(roomId, username)) { send("ERR NOT_IN_ROOM"); return; }
            roomDao.removeMember(roomId, username);
            rooms.leave(roomId, this);
            send("OK ROOM_LEAVE " + roomName);
        } catch (Exception e){ send("ERR ROOM_LEAVE_FAILED"); }
    }

    private void cmdRoomAdd(String arg){
        if (!ensureLoggedIn()) return;
        String[] p = arg.split("\\s+", 2);
        if (p.length < 2){ send("ERR ROOM_ADD usage: ROOM.ADD <room> <user>"); return; }

        String roomName = p[0].trim();
        String userToAdd = p[1].trim();              // ❶ TRIM

        try {
            Long roomId = roomDao.findRoomIdByName(roomName);
            if (roomId == null) { send("ERR ROOM_NOT_FOUND"); return; }
            if (!roomDao.isOwner(roomId, username)) { send("ERR FORBIDDEN"); return; }

            roomDao.addMember(roomId, userToAdd);
            rooms.cache(roomName, roomId);
            send("OK ROOM_ADD " + userToAdd + " TO " + roomName);

            // ❷ chuẩn hoá key nếu bạn login luôn dùng lowercase:
            String key = userToAdd; // hoặc userToAdd.toLowerCase() nếu login cũng lower()
            ClientHandler t = online.get(key);
            if (t != null) {
                rooms.join(roomId, t);               // ❸ JOIN RUNTIME NGAY
                t.send("[ROOM] You were added to " + roomName);
            }
        } catch (Exception e){ send("ERR ROOM_ADD_FAILED"); }
    }


    private void cmdRoomMsg(String arg){
        if (!ensureLoggedIn()) return;
        String[] p = arg.split("\\s+", 2);
        if (p.length < 2){ send("ERR ROOM_MSG usage: ROOM.MSG <room> <text>"); return; }
        String roomName = p[0]; String text = p[1];
        try {
            Long roomId = roomDao.findRoomIdByName(roomName);
            if (roomId == null) { send("ERR ROOM_NOT_FOUND"); return; }
            if (!roomDao.isMember(roomId, username)) { send("ERR NOT_IN_ROOM"); return; }

            try { messageDao.saveRoomMessage(roomId, username, text); } catch (SQLException ignore) {}

            for (var member : rooms.members(roomId)) {
                member.send("[ROOM "+roomName+"] "+username+": "+text);
            }
            send("OK ROOM_MSG");
        } catch (Exception e){ send("ERR ROOM_MSG_FAILED"); }
    }

    private void cmdRoomMembers(String arg){
        if (!ensureLoggedIn()) return;
        String roomName = arg.trim();
        if (roomName.isEmpty()) { send("ERR ROOM_MEMBERS name_required"); return; }
        try {
            Long roomId = roomDao.findRoomIdByName(roomName);
            if (roomId == null) { send("ERR ROOM_NOT_FOUND"); return; }
            var mem = roomDao.listMembers(roomId);
            send("ROOM_MEMBERS " + roomName + " " + String.join(",", mem));
        } catch (Exception e){ send("ERR ROOM_MEMBERS_FAILED"); }
    }

    // ====== helpers cũ giữ nguyên ======

    private void handleLogin(String arg) {
        String u = arg.trim();
        if (u.isEmpty() || online.containsKey(u)) {
            send("ERR LOGIN");
            return;
        }
        username = u;
        online.put(username, this);
        try {
            var roomIds = roomDao.listRoomIdsOfUser(username);
            for (Long rid : roomIds) rooms.join(rid, this);
        } catch (SQLException ignore) {}

        send("OK LOGIN " + username);
        broadcast("🔵 " + username + " joined", true);

        try {
            var pending = messageDao.loadQueued(username);
            for (var f : pending) send("[DM] " + f.sender + ": " + f.body);
            if (!pending.isEmpty()) send("[SYS] Delivered " + pending.size() + " offline messages.");
        } catch (SQLException e) {
            send("ERR OFFLINE_DELIVERY");
            e.printStackTrace();
        }
    }

    private boolean ensureLoggedIn() {
        if (username == null) { send("ERR NOT_LOGGED_IN"); return false; }
        return true;
    }

    public void send(String msg) { if (out != null) out.println(msg); }

    private void broadcast(String msg, boolean excludeSelf) {
        for (ClientHandler c : clients) {
            if (excludeSelf && c == this) continue;
            c.send("[ALL] " + msg);
        }
    }

    private void cleanup() {
        if (username != null) {
            online.remove(username, this);
            broadcast("🔴 " + username + " left", true);
        }
        rooms.removeEverywhere(this);
        clients.remove(this);
        close();
        System.out.println("⬅ Client disconnected: " + socket.getRemoteSocketAddress());
    }

    public void close() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}

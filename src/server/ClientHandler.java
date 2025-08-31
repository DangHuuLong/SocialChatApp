package server;

import server.dao.MessageDao;
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

    private BufferedReader in;
    private PrintWriter out;
    private String username = null;

    public ClientHandler(Socket socket,
                         Set<ClientHandler> clients,
                         Map<String, ClientHandler> online,
                         MessageDao messageDao) {
        this.socket = socket;
        this.clients = clients;
        this.online  = online;
        this.messageDao = messageDao;
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

                // Tách theo mọi whitespace, không phân biệt hoa/thường
                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toUpperCase();   // LOGIN / MSG / DM / WHO / QUIT
                String arg = (parts.length > 1) ? parts[1] : "";

                switch (cmd) {
                    case "LOGIN" -> handleLogin(arg);

                    case "MSG" -> {
                        if (!ensureLoggedIn()) break;
                        String msg = arg; // phần còn lại
                        broadcast(username + ": " + msg, false);
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


                    case "WHO" -> {
                        // WHO có thể cho phép khi chưa login cũng được; tuỳ chọn:
                        send("ONLINE " + String.join(",", online.keySet()));
                    }

                    case "QUIT" -> {
                        send("BYE");
                        return; // thoát run()
                    }

                    default -> send("ERR UNKNOWN_CMD");
                }
            }
        } catch (IOException ignored) {
        } finally {
            cleanup();
        }
    }

    /* ========= Helpers ========= */

    private void handleLogin(String arg) {
        String u = arg.trim();
        if (u.isEmpty() || online.containsKey(u)) {
            send("ERR LOGIN");
            return;
        }
        username = u;
        online.put(username, this);
        send("OK LOGIN " + username);
        broadcast("🔵 " + username + " joined", true);

        // Giao tin offline từ DB
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
        if (username == null) {
            send("ERR NOT_LOGGED_IN");
            return false;
        }
        return true;
    }

    public void send(String msg) {
        if (out != null) out.println(msg);
    }

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
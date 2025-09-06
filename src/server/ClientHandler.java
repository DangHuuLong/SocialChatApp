package server;

import server.dao.MessageDao;
import server.signaling.CallRouter;
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
                
                if (line.startsWith("CALL_")) {
                    if (!ensureLoggedIn()) { 
                        send("ERR NOT_LOGGED_IN"); 
                        continue; 
                    }
                    handleCallCommand(line);  
                    continue;                 
                }

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
                        send("ONLINE " + String.join(",", online.keySet()));
                    }

                    case "QUIT" -> {
                        send("BYE");
                        return; 
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
        CallRouter.getInstance().register(username, this); 
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
        	CallRouter.getInstance().unregister(username, this);
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
    
    // Call
    public void sendLine(String line) {
        try {
            out.println(line); 
            out.flush();
        } catch (Exception e) {
        	e.printStackTrace();
        }
    }
    
    private void handleCallCommand(String line) {
        // Format client->server:
        // CALL_INVITE <toUser> <callId>
        // CALL_ACCEPT <toUser> <callId>
        // CALL_REJECT <toUser> <callId>
        // CALL_CANCEL <toUser> <callId>
        // CALL_BUSY   <toUser> <callId>
        // CALL_END    <toUser> <callId>
        //
        // CALL_OFFER  <toUser> <callId> <b64sdp>
        // CALL_ANSWER <toUser> <callId> <b64sdp>
        // CALL_ICE    <toUser> <callId> <b64cand>

        final var router = CallRouter.getInstance();

        // pay attention: keep payload as one token (limit=4)
        String[] parts = line.split(" ", 4);
        String cmd = parts[0];

        // sanity check common 3-part commands
        if (cmd.equals("CALL_OFFER") || cmd.equals("CALL_ANSWER") || cmd.equals("CALL_ICE")) {
            if (parts.length < 4) { sendLine("ERR BAD_CALL_SYNTAX"); return; }
        } else {
            if (parts.length < 3) { sendLine("ERR BAD_CALL_SYNTAX"); return; }
        }

        String toUser = parts[1];
        String callId = parts[2];

        switch (cmd) {
            case "CALL_INVITE" -> router.invite(username, toUser, callId);
            case "CALL_ACCEPT" -> router.accept(username, toUser, callId);
            case "CALL_REJECT" -> router.reject(username, toUser, callId);
            case "CALL_CANCEL" -> router.cancel(username, toUser, callId);
            case "CALL_BUSY"   -> router.busy(username, toUser, callId);
            case "CALL_END"    -> router.end(username, toUser, callId);

            case "CALL_OFFER"  -> {
                String b64 = parts[3];
                router.offer(username, toUser, callId, b64);
            }
            case "CALL_ANSWER" -> {
                String b64 = parts[3];
                router.answer(username, toUser, callId, b64);
            }
            case "CALL_ICE"    -> {
                String b64 = parts[3];
                router.ice(username, toUser, callId, b64);
            }

            default -> sendLine("ERR UNKNOWN_CALL_CMD");
        }
    }


}
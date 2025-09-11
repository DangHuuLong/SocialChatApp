package server;

import server.dao.MessageDao;
import server.signaling.CallRouter;
import common.Frame;
import common.FrameIO;
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

    // ===== [OLD]
    private BufferedReader in;
    private PrintWriter out;
    private String username = null;

    // ===== [NEW] stream nhị phân
    private DataInputStream binIn;
    private DataOutputStream binOut;

    // ===== [NEW] thư mục lưu
    private static final File UPLOAD_DIR = new File("uploads");

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
            // ===== [MOD-KEEP] khởi tạo text + binary trên cùng raw stream
            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();
            in  = new BufferedReader(new InputStreamReader(rawIn, "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);
            binIn  = new DataInputStream(new BufferedInputStream(rawIn));
            binOut = new DataOutputStream(new BufferedOutputStream(rawOut));
            if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();

            send("HELLO");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // [OLD] signaling call (giữ nguyên)
                if (line.startsWith("CALL_")) {
                    if (!ensureLoggedIn()) { send("ERR NOT_LOGGED_IN"); continue; }
                    handleCallCommand(line);
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toUpperCase();
                String arg = (parts.length > 1) ? parts[1] : "";

                switch (cmd) {
                    case "LOGIN" -> handleLogin(arg);

                    case "MSG" -> {
                        if (!ensureLoggedIn()) break;
                        String msg = arg;
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

                    case "WHO" -> { send("ONLINE " + String.join(",", online.keySet())); }

                    case "QUIT" -> { send("BYE"); return; }

                    // ===== [NEW] bắt đầu nhận nhị phân sau lệnh text =====
                    case "SEND_FILE" -> {
                        if (!ensureLoggedIn()) break;
                        try {
                            handleFileTransfer();   // đọc 1 META + nhiều CHUNK (đến last=true)
                            send("OK FILE_SAVED");
                        } catch (IOException e) {
                            send("ERR FILE_FAIL");
                            e.printStackTrace();
                        }
                    }
                    case "SEND_AUDIO" -> {
                        if (!ensureLoggedIn()) break;
                        try {
                            handleAudioTransfer();  // đọc 1 META + nhiều CHUNK
                            send("OK AUDIO_SAVED");
                        } catch (IOException e) {
                            send("ERR AUDIO_FAIL");
                            e.printStackTrace();
                        }
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

    public void send(String msg) { if (out != null) out.println(msg); }

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
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }

    // ===== [OLD] signaling qua CallRouter =====
    public void sendLine(String line) {
        try { out.println(line); out.flush(); } catch (Exception e) { e.printStackTrace(); }
    }
    private void handleCallCommand(String line) {
        final var router = CallRouter.getInstance();
        String[] parts = line.split(" ", 4);
        String cmd = parts[0];

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
            case "CALL_OFFER"  -> { String b64 = parts[3]; router.offer(username, toUser, callId, b64); }
            case "CALL_ANSWER" -> { String b64 = parts[3]; router.answer(username, toUser, callId, b64); }
            case "CALL_ICE"    -> { String b64 = parts[3]; router.ice(username, toUser, callId, b64); }
            default -> sendLine("ERR UNKNOWN_CALL_CMD");
        }
    }

    // ===== [NEW] util JSON tối giản (tránh lib) =====
    private static String jsonGet(String json, String key) {
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }

    // ===== [NEW] xử lý FILE =====
    private void handleFileTransfer() throws IOException {
        Frame meta = FrameIO.read(binIn);
        if (meta == null || meta.type != MessageType.FILE_META)
            throw new IOException("Expected FILE_META");

        String body = meta.body == null ? "" : meta.body;
        String fileId = jsonGet(body, "fileId");
        String name   = jsonGet(body, "name");
        String sizeS  = jsonGet(body, "size");
        long size     = 0;
        try { size = Long.parseLong(sizeS == null ? "0" : sizeS); } catch (Exception ignore) {}

        if (size > Frame.MAX_FILE_BYTES) throw new IOException("File too large");

        if (fileId == null || fileId.isEmpty()) fileId = java.util.UUID.randomUUID().toString();
        if (name == null || name.isEmpty()) name = "file-" + fileId;

        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                (int)Math.min(size > 0 ? size : (1<<20), Frame.MAX_FILE_BYTES));
        int expectedSeq = 0;
        while (true) {
            Frame ch = FrameIO.read(binIn);
            if (ch == null || ch.type != MessageType.FILE_CHUNK)
                throw new IOException("Expected FILE_CHUNK");
            if (!fileId.equals(ch.transferId))
                throw new IOException("Mismatched fileId");
            if (ch.seq != expectedSeq++)
                throw new IOException("Out-of-order chunk");
            if (baos.size() + (ch.bin == null ? 0 : ch.bin.length) > Frame.MAX_FILE_BYTES)
                throw new IOException("File exceeds limit");

            if (ch.bin != null) baos.write(ch.bin);
            if (ch.last) break;
        }

        File outFile = uniquePath(name);
        try (FileOutputStream fo = new FileOutputStream(outFile)) {
            baos.writeTo(fo);
        }

        String to = meta.recipient;
        ClientHandler target = (to == null) ? null : online.get(to);
        if (target != null) {
            target.send("[FILE] from " + username + " -> " + to + " : " + outFile.getName());
        }
    }

    // ===== [NEW] xử lý AUDIO =====
    private void handleAudioTransfer() throws IOException {
        Frame meta = FrameIO.read(binIn);
        if (meta == null || meta.type != MessageType.AUDIO_META)
            throw new IOException("Expected AUDIO_META");

        String body = meta.body == null ? "" : meta.body;
        String audioId   = jsonGet(body, "audioId");
        String durationS = jsonGet(body, "durationSec");
        String sizeS     = jsonGet(body, "size");

        int durationSec = 0;
        try { durationSec = Integer.parseInt(durationS == null ? "0" : durationS); } catch (Exception ignore) {}
        long size = 0;
        try { size = Long.parseLong(sizeS == null ? "0" : sizeS); } catch (Exception ignore) {}

        if (durationSec > Frame.MAX_AUDIO_SECONDS) throw new IOException("Audio too long");
        if (size > Frame.MAX_FILE_BYTES)         throw new IOException("Audio bytes exceed limit");

        if (audioId == null || audioId.isEmpty()) audioId = java.util.UUID.randomUUID().toString();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(
                (int)Math.min(size > 0 ? size : (1<<20), Frame.MAX_FILE_BYTES));
        int expectedSeq = 0;
        while (true) {
            Frame ch = FrameIO.read(binIn);
            if (ch == null || ch.type != MessageType.AUDIO_CHUNK)
                throw new IOException("Expected AUDIO_CHUNK");
            if (!audioId.equals(ch.transferId))
                throw new IOException("Mismatched audioId");
            if (ch.seq != expectedSeq++)
                throw new IOException("Out-of-order chunk");
            if (baos.size() + (ch.bin == null ? 0 : ch.bin.length) > Frame.MAX_FILE_BYTES)
                throw new IOException("Audio exceeds limit");

            if (ch.bin != null) baos.write(ch.bin);
            if (ch.last) break;
        }

        File outFile = uniquePath("audio-" + audioId + ".aud"); // tối giản: .aud
        try (FileOutputStream fo = new FileOutputStream(outFile)) {
            baos.writeTo(fo);
        }

        String to = meta.recipient;
        ClientHandler target = (to == null) ? null : online.get(to);
        if (target != null) {
            target.send("[AUDIO] from " + username + " -> " + to + " : " + outFile.getName());
        }
    }

    private File uniquePath(String name) {
        File f = new File(UPLOAD_DIR, name);
        if (!f.exists()) return f;
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        int i = 1;
        while (true) {
            File g = new File(UPLOAD_DIR, base + "-" + i + ext);
            if (!g.exists()) return g;
            i++;
        }
    }
}

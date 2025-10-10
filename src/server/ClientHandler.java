package server;

import server.dao.MessageDao;
import server.signaling.CallRouter;
import common.Frame;
import common.FrameIO;
import common.MessageType;
import server.dao.FileDao;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Set<ClientHandler> clients;
    private final Map<String, ClientHandler> online;
    private final MessageDao messageDao;
    private final FileDao fileDao;

    private DataInputStream binIn;
    private DataOutputStream binOut;

    private String username = null;
    private static final File UPLOAD_DIR = new File("uploads");
    private static final Map<String, String> fileNameMap = new ConcurrentHashMap<>();

    // ==== State tạm cho 1 phiên upload file ====
    private String upFileId;
    private String upToUser;
    private String upOrigName;
    private String upMime;
    private long upDeclaredSize;
    private int upExpectedSeq;
    private long upWritten;
    private BufferedOutputStream upOut;

    public ClientHandler(Socket socket,
                         Set<ClientHandler> clients,
                         Map<String, ClientHandler> online,
                         MessageDao messageDao, FileDao fileDao) {
        this.socket = socket;
        this.clients = clients;
        this.online = online;
        this.messageDao = messageDao;
        this.fileDao = fileDao;
    }

    @Override
    public void run() {
        try {
            socket.setTcpNoDelay(true);
            binIn = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            binOut = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();

            while (true) {
                Frame f = FrameIO.read(binIn);
                if (f == null) {
                    System.out.println("[SERVER] FrameIO.read returned null, client likely disconnected");
                    break;
                }
                System.out.println("[SERVER] Received frame: type=" + f.type + ", transferId=" + f.transferId);
                switch (f.type) {
                    case REGISTER, LOGIN -> handleLogin(f);
                    case DM -> handleDirectMessage(f);
                    case HISTORY -> handleHistory(f);
                    case FILE_META, FILE_CHUNK -> handleFile(f);
                    case AUDIO_META, AUDIO_CHUNK -> handleAudio(f);
                    case CALL_INVITE, CALL_ACCEPT, CALL_REJECT,
                         CALL_CANCEL, CALL_BUSY, CALL_END,
                         CALL_OFFER, CALL_ANSWER, CALL_ICE -> handleCall(f);
                    case DELETE_MSG -> handleDeleteMessage(f);
                    case DOWNLOAD_FILE -> handleDownloadFiles(f);
                    case EDIT_MSG -> handleEditMessage(f);
                    case SEARCH -> handleSearch(f);
                    case FILE_HISTORY -> handleFileHistory(f);
                    case DELETE_FILE -> handleDeleteFile(f);
                    case AUDIO_HISTORY -> handleAudioHistory(f);
                    case DOWNLOAD_AUDIO -> handleDownloadAudio(f);
                    case DELETE_AUDIO -> handleDeleteAudio(f);

                    default -> System.out.println("[SERVER] Unknown frame: " + f.type);
                }
            }
        } catch (SocketException se) {
            System.out.println("⬅ Client disconnected: " + socketSafe() + " (" + se.getMessage() + ")");
        } catch (EOFException eof) {
            System.out.println("⬅ Client disconnected: " + socketSafe() + " (EOF)");
        } catch (IOException e) {
            System.err.println("[SERVER] IO error: " + e.getMessage());
        } finally {
            cleanup();
        }
    }

    /* ================= LOGIN ================= */
    private void handleLogin(Frame f) {
        String u = f.sender;
        if (u == null || u.isBlank() || online.containsKey(u)) {
            sendFrame(Frame.error("LOGIN_FAIL"));
            return;
        }
        username = u;
        online.put(username, this);
        CallRouter.getInstance().register(username, this);

        sendFrame(Frame.ack("OK LOGIN " + username));
        broadcast("🔵 " + username + " joined", true);

        try {
            var pending = messageDao.loadQueued(username);
            for (var m : pending) {
                sendFrame(m); // m.transferId đã là id DB
            }
            if (!pending.isEmpty())
                sendFrame(Frame.ack("Delivered " + pending.size() + " offline messages"));
        } catch (SQLException e) {
            sendFrame(Frame.error("OFFLINE_DELIVERY_FAIL"));
            e.printStackTrace();
        }
    }

    /* ================= DIRECT MESSAGE ================= */
    private void handleDirectMessage(Frame f) {
        String to = f.recipient;
        if (to == null || to.isBlank()) {
            sendFrame(Frame.error("BAD_DM"));
            return;
        }

        try {
            long id;
            ClientHandler target = online.get(to);
            if (target != null) {
                id = messageDao.saveSentReturnId(f);
                f.transferId = String.valueOf(id);
                target.sendFrame(f); // gửi cho người nhận kèm id
                Frame ack = Frame.ack("OK DM");
                ack.transferId = String.valueOf(id); // trả id cho người gửi để map UI
                sendFrame(ack);
            } else {
                id = messageDao.saveQueuedReturnId(f);
                Frame ack = Frame.ack("OK QUEUED");
                ack.transferId = String.valueOf(id);
                sendFrame(ack);
            }
        } catch (SQLException e) {
            sendFrame(Frame.error("DM_SAVE_FAIL"));
            e.printStackTrace();
        }
    }

    /* ================= DELETE ================= */
    private void handleDeleteMessage(Frame f) {
        try {
            long id = parseLongSafe(f.body, 0L);
            if (id <= 0) {
                sendFrame(Frame.error("BAD_ID"));
                return;
            }
            String peer = messageDao.deleteByIdReturningPeer(id, username);
            try {
                var fileRow = fileDao.getFileByMessageId(id);
                if (fileRow != null) {
                    // Delete from DB
                    fileDao.deleteFileByMessageId(id);
                    // Delete actual file
                    File toDelete = new File(fileRow.filePath);
                    if (toDelete.exists() && toDelete.delete()) {
                        System.out.println("[FILE] Deleted " + toDelete.getName());
                    } else {
                        System.out.println("[FILE] No file to delete for messageId=" + id);
                    }
                }
            } catch (SQLException ex) {
                System.err.println("[DB] Could not delete file metadata for msgId=" + id + ": " + ex.getMessage());
            }

            if (peer == null) {
                sendFrame(Frame.error("DENIED_OR_NOT_FOUND"));
                return;
            }

            Frame ack = Frame.ack("OK DELETE");
            ack.transferId = String.valueOf(id);
            sendFrame(ack);

            Frame evt = new Frame(MessageType.DELETE_MSG, username, peer, "");
            evt.transferId = String.valueOf(id);
            ClientHandler peerHandler = online.get(peer);
            if (peerHandler != null) {
                peerHandler.sendFrame(evt);
            }
        } catch (Exception e) {
            sendFrame(Frame.error("DELETE_FAIL"));
        }
    }
    
    /* ================= EDIT ================= */
    private void handleEditMessage(Frame f) {
        try {
            long id = parseLongSafe(f.transferId != null ? f.transferId : f.body, 0L);
            if (id <= 0) { sendFrame(Frame.error("BAD_ID")); return; }
            String newBody = (f.body == null) ? "" : f.body;

            String peer = messageDao.updateByIdReturningPeer(id, username, newBody);
            if (peer == null) {
                sendFrame(Frame.error("DENIED_OR_NOT_FOUND"));
                return;
            }

            // ACK cho người gửi
            Frame ack = Frame.ack("OK EDIT");
            ack.transferId = String.valueOf(id);
            sendFrame(ack);

            // Thông báo cho người nhận cập nhật UI
            Frame evt = new Frame(MessageType.EDIT_MSG, username, peer, newBody);
            evt.transferId = String.valueOf(id);
            ClientHandler peerHandler = online.get(peer);
            if (peerHandler != null) peerHandler.sendFrame(evt);
        } catch (Exception e) {
            sendFrame(Frame.error("EDIT_FAIL"));
        }
    }


    /* ================= SEARCH MESSAGE ================= */
    private void handleSearch(Frame f){
        String peer = f.recipient;
        String q = jsonGet(f.body, "q");
        if (q == null) q = "";
        int limit = (f.seq > 0) ? f.seq : 50;
        int offset = (int) parseLongSafe(jsonGet(f.body, "offset"), 0);
        try{
            var rows = messageDao.searchConversation(username, peer, q, limit, offset);
            for (var r : rows){
                Frame hit = new Frame(common.MessageType.SEARCH_HIT, r.sender, r.recipient, r.body);
                hit.transferId = String.valueOf(r.id);
                sendFrame(hit);
            }
            sendFrame(Frame.ack("OK SEARCH " + rows.size()));
        }catch(Exception e){
            sendFrame(Frame.error("SEARCH_FAIL"));
        }
    }

    /* ================= HISTORY ================= */
    private void handleHistory(Frame f) {
        String peer = f.recipient;
        int limit = 50;
        try { limit = Integer.parseInt(f.body); } catch (Exception ignore) {}

        try {
            var rows = messageDao.loadConversation(username, peer, limit);
            for (var r : rows) {
                boolean incoming = !r.sender.equals(username);
                String txt = incoming
                        ? "[HIST IN] " + r.sender + ": " + r.body
                        : "[HIST OUT] " + r.body;
                Frame hist = new Frame(MessageType.HISTORY, r.sender, r.recipient, txt);
                hist.transferId = String.valueOf(r.id);
                sendFrame(hist);
            }
            sendFrame(Frame.ack("OK HISTORY " + rows.size()));
        } catch (SQLException e) {
            sendFrame(Frame.error("HISTORY_FAIL"));
            e.printStackTrace();
        }
    }

    /* ================= FILE ================= */
    private void handleFile(Frame f) {
        try {
            if (f.type == MessageType.FILE_META) {
                String body = (f.body == null ? "" : f.body);
                String from = pickJson(body, "from");
                String to = pickJson(body, "to");
                String name = pickJson(body, "name");
                String mime = pickJson(body, "mime");
                String fid = pickJson(body, "fileId");
                long size = parseLongSafe(pickJson(body, "size"), 0);

                if (fid == null || fid.isBlank()) fid = java.util.UUID.randomUUID().toString();
                if (name == null || name.isBlank()) name = "file-" + fid;
                if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                if (size > Frame.MAX_FILE_BYTES) throw new IOException("file too large");

                if (upOut != null) { try { upOut.close(); } catch (Exception ignore) {} }
                upFileId = fid;
                upToUser = to;
                upOrigName = name;
                upMime = mime;
                upDeclaredSize = size;
                upExpectedSeq = 0;
                upWritten = 0L;

                if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();
                File outFile = new File(UPLOAD_DIR, sanitizeFilename(fid));
                fileNameMap.put(fid, name);
                upOut = new BufferedOutputStream(new FileOutputStream(outFile));

                System.out.println("[SERVER] FILE META " + body);
                return;
            }

            if (f.type == MessageType.FILE_CHUNK) {
                if (upOut == null || upFileId == null) {
                    throw new IOException("FILE_CHUNK without META");
                }
                if (!upFileId.equals(f.transferId)) {
                    throw new IOException("Mismatched fileId");
                }
                if (f.seq != upExpectedSeq) {
                    throw new IOException("Out-of-order chunk: got=" + f.seq + " need=" + upExpectedSeq);
                }

                int len = (f.bin == null ? 0 : f.bin.length);
                if (upWritten + len > Frame.MAX_FILE_BYTES) {
                    throw new IOException("File exceeds limit");
                }
                if (len > 0) {
                    upOut.write(f.bin);
                    upWritten += len;
                }
                upExpectedSeq++;

                System.out.println("[SERVER] FILE CHUNK id=" + f.transferId + " seq=" + f.seq);

                if (f.last) {
                    upOut.flush();
                    upOut.close();
                    upOut = null;

                    Frame ack = Frame.ack("FILE_SAVED " + upWritten + "B " + upMime);
                    ack.transferId = upFileId;
                    sendFrame(ack);

                    try {
                        Frame fileMsg = new Frame(MessageType.DM, username, upToUser, "[FILE] " + upOrigName);
                        long msgId = messageDao.saveSentReturnId(fileMsg);

                        String filePath = new File(UPLOAD_DIR, sanitizeFilename(upFileId)).getAbsolutePath();
                        fileDao.saveFile(msgId, upOrigName, filePath, upMime, upWritten);

                        System.out.println("[DB] File metadata saved: msgId=" + msgId + ", path=" + filePath);
                    } catch (SQLException sqle) {
                        System.err.println("[DB] Failed to save file metadata: " + sqle.getMessage());
                    }

                    if (upToUser != null && !upToUser.isBlank()) {
                        ClientHandler target = online.get(upToUser);
                        if (target != null) {
                            String savedName = sanitizeFilename(upOrigName);
                            String json = "{"
                                    + "\"from\":\"" + escJson(username) + "\","
                                    + "\"to\":\""   + escJson(upToUser) + "\","
                                    + "\"id\":\""   + escJson(upFileId) + "\","
                                    + "\"name\":\"" + escJson(savedName) + "\","
                                    + "\"mime\":\"" + escJson(upMime) + "\","
                                    + "\"bytes\":"  + upWritten
                                    + "}";
                            Frame evt = new Frame(MessageType.FILE_EVT, username, upToUser, json);
                            target.sendFrame(evt);
                        }
                    }

                    System.out.println("[SERVER] FILE OK id=" + upFileId + " -> " + upOrigName + " (" + upWritten + "B)");
                    upFileId = null;
                    upToUser = null;
                    upOrigName = null;
                    upMime = null;
                    upDeclaredSize = 0;
                    upExpectedSeq = 0;
                    upWritten = 0;
                }
            }
        } catch (IOException e) {
            try { if (upOut != null) upOut.close(); } catch (Exception ignore) {}
            upOut = null; upFileId = null;
            System.err.println("[SERVER] FILE FAIL: " + e.getMessage());
            sendFrame(Frame.error("FILE_FAIL"));
        }
    }
    
    public void handleDownloadFiles(Frame f) {
        String fileIdStr = f.body;
        long fileId = parseLongSafe(fileIdStr, 0L);
        if (fileId <= 0) {
            sendFrame(Frame.error("INVALID_FILE_ID"));
            return;
        }

        try {
            // ✅ Step 1: Load metadata from DB
            var fileRow = fileDao.getFileByMessageId(fileId);
            if (fileRow == null) {
                sendFrame(Frame.error("FILE_NOT_FOUND_DB"));
                System.err.println("[DB] No file record for id=" + fileId);
                return;
            }

            File file = new File(fileRow.filePath);
            if (!file.exists()) {
                sendFrame(Frame.error("FILE_NOT_FOUND_DISK"));
                System.err.println("[SERVER] File missing on disk: " + file.getAbsolutePath());
                return;
            }

            // ✅ Step 2: Send file metadata frame first
            String mime = (fileRow.mimeType != null) ? fileRow.mimeType : "application/octet-stream";
            String name = (fileRow.fileName != null) ? fileRow.fileName : ("file-" + fileId);

            Frame meta = Frame.fileMeta(
                    username,
                    "",             // recipient not needed for download
                    name,
                    mime,
                    String.valueOf(fileId),
                    file.length()
            );
            sendFrame(meta);

            // ✅ Step 3: Send file chunks
            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[Frame.CHUNK_SIZE];
                int n, seq = 0;
                long rem = file.length();
                while ((n = fis.read(buf)) != -1) {
                    rem -= n;
                    boolean last = (rem == 0);
                    byte[] slice = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    Frame ch = Frame.fileChunk(username, "", String.valueOf(fileId), seq++, last, slice);
                    sendFrame(ch);
                }
            }

            System.out.println("[SERVER] File sent successfully: " + fileRow.fileName);

        } catch (SQLException e) {
            System.err.println("[DB] Error fetching file info: " + e.getMessage());
            sendFrame(Frame.error("DB_ERROR_FILE_FETCH"));
        } catch (IOException e) {
            System.err.println("[SERVER] Download failed: " + e.getMessage());
            sendFrame(Frame.error("DOWNLOAD_FAIL"));
        }
    }

    private void handleFileHistory(Frame f) {
        int limit = 5;   
        int offset = 0;
        try {

            if (f.body != null && !f.body.isBlank()) {
                String body = f.body.trim();
                String limitStr = jsonGet(body, "limit");
                String offsetStr = jsonGet(body, "offset");
                if (limitStr != null) limit = Integer.parseInt(limitStr);
                if (offsetStr != null) offset = Integer.parseInt(offsetStr);
                if (limitStr == null && offsetStr == null) {
                    // fallback to plain number
                    limit = Integer.parseInt(body);
                }
            }
        } catch (Exception ignore) {}

        try {
            var rows = fileDao.listFilesByUserPaged(username, limit, offset);
            for (var r : rows) {
                String info = String.format(
                    "[FILE HIST] %s (%d bytes, %s)",
                    r.fileName, r.fileSize,
                    (r.uploadedAt != null ? r.uploadedAt.toString() : "unknown")
                );
                Frame hist = new Frame(MessageType.FILE_HISTORY, username, username, info);
                hist.transferId = String.valueOf(r.id);
                sendFrame(hist);
            }
            sendFrame(Frame.ack("OK FILE_HISTORY " + rows.size()));
        } catch (SQLException e) {
            sendFrame(Frame.error("FILE_HISTORY_FAIL"));
            e.printStackTrace();
        }
    }
    
    private void handleDeleteFile(Frame f) {
        long msgId = parseLongSafe(f.body, 0L);
        if (msgId <= 0) {
            sendFrame(Frame.error("INVALID_FILE_ID"));
            return;
        }

        try {
            boolean deleted = fileDao.deleteFileByMessageId(msgId);
            if (deleted) {
                sendFrame(Frame.ack("OK FILE_DELETED"));

                // Optionally broadcast to peer so their UI removes file bubble
                Frame evt = new Frame(MessageType.DELETE_FILE, username, "", "");
                evt.transferId = String.valueOf(msgId);
                broadcast(evt.toString(), true);

                System.out.println("[SERVER] File deleted for messageId=" + msgId);
            } else {
                sendFrame(Frame.error("FILE_NOT_FOUND"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] File delete error: " + e.getMessage());
            sendFrame(Frame.error("DB_FILE_DELETE_FAIL"));
        }
    }

    /* ================= AUDIO ================= */
    private record AudioResult(String id, String to, String savedName, long bytes, int durationSec) {}
    /*change*/
    private void handleAudio(Frame f) {
        try {
            if (f.type == MessageType.AUDIO_META) {
                // 1️⃣ Receive and store the audio file in uploads/
                AudioResult a = receiveAudio(f);

                // 2️⃣ Save message and file metadata to database
                try {
                    // Save message record first (just like file message)
                    Frame audioMsg = new Frame(MessageType.DM, username, a.to, "[AUDIO]");
                    long msgId = messageDao.saveSentReturnId(audioMsg);

                    // Build absolute file path for the stored audio
                    String audioPath = new File(UPLOAD_DIR, sanitizeFilename(a.id)).getAbsolutePath();

                    // Save to DB using FileDao
                    fileDao.saveAudio(
                            msgId,
                            a.savedName,
                            audioPath,
                            "audio/wav",           // or dynamic MIME if you detect from client
                            a.bytes,
                            a.durationSec
                    );

                    System.out.println("[DB] Audio metadata saved: msgId=" + msgId + ", duration=" + a.durationSec + "s");

                } catch (SQLException sqle) {
                    System.err.println("[DB] Failed to save audio metadata: " + sqle.getMessage());
                }

                // 3️⃣ Send confirmation ACK to the sender
                Frame ack = Frame.ack("AUDIO_SAVED " + a.bytes + "B " + a.durationSec + "s");
                ack.transferId = a.id;
                sendFrame(ack);

                // 4️⃣ Notify the recipient so their UI shows new audio bubble
                notifyIncomingAudio(a);

                System.out.println("[SERVER] AUDIO OK id=" + a.id + " -> " + a.savedName + " (" + a.bytes + "B)");

            } else {
                System.out.println("[SERVER] Unexpected AUDIO_CHUNK without META");
            }

        } catch (IOException e) {
            sendFrame(Frame.error("AUDIO_FAIL"));
            System.err.println("[SERVER] AUDIO FAIL: " + e.getMessage());
        }
    }
    /* ================= AUDIO HISTORY ================= */
    private void handleAudioHistory(Frame f) {
        int limit = 5;
        int offset = 0;
        try {
            if (f.body != null && !f.body.isBlank()) {
                String body = f.body.trim();
                String limitStr = jsonGet(body, "limit");
                String offsetStr = jsonGet(body, "offset");
                if (limitStr != null) limit = Integer.parseInt(limitStr);
                if (offsetStr != null) offset = Integer.parseInt(offsetStr);
                if (limitStr == null && offsetStr == null)
                    limit = Integer.parseInt(body);
            }
        } catch (Exception ignore) {}

        try {
            var rows = fileDao.listAudiosByUserPaged(username, limit, offset);
            for (var r : rows) {
                String info = String.format(
                    "[AUDIO HIST] %s (%d bytes, %ss)",
                    r.fileName,
                    r.fileSize,
                    (r.durationSec != null ? r.durationSec : "n/a")
                );
                Frame hist = new Frame(MessageType.AUDIO_HISTORY, username, username, info);
                hist.transferId = String.valueOf(r.id);
                sendFrame(hist);
            }
            sendFrame(Frame.ack("OK AUDIO_HISTORY " + rows.size()));
        } catch (SQLException e) {
            sendFrame(Frame.error("AUDIO_HISTORY_FAIL"));
            e.printStackTrace();
        }
    }

    /* ================= DOWNLOAD AUDIO ================= */
    private void handleDownloadAudio(Frame f) {
        long msgId = parseLongSafe(f.body, 0L);
        if (msgId <= 0) {
            sendFrame(Frame.error("INVALID_AUDIO_ID"));
            return;
        }

        try {
            var audioRow = fileDao.getAudioByMessageId(msgId);
            if (audioRow == null) {
                sendFrame(Frame.error("AUDIO_NOT_FOUND_DB"));
                System.err.println("[DB] No audio record for id=" + msgId);
                return;
            }

            File file = new File(audioRow.filePath);
            if (!file.exists()) {
                sendFrame(Frame.error("AUDIO_NOT_FOUND_DISK"));
                System.err.println("[SERVER] Audio missing on disk: " + file.getAbsolutePath());
                return;
            }

            String mime = (audioRow.mimeType != null) ? audioRow.mimeType : "audio/wav";
            String name = (audioRow.fileName != null) ? audioRow.fileName : ("audio-" + msgId);

            // send meta frame
            Frame meta = Frame.fileMeta(username, "", name, mime, String.valueOf(msgId), file.length());
            sendFrame(meta);

            // send audio chunks
            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[Frame.CHUNK_SIZE];
                int n, seq = 0;
                long rem = file.length();
                while ((n = fis.read(buf)) != -1) {
                    rem -= n;
                    boolean last = (rem == 0);
                    byte[] slice = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                    Frame ch = Frame.fileChunk(username, "", String.valueOf(msgId), seq++, last, slice);
                    sendFrame(ch);
                }
            }

            System.out.println("[SERVER] Audio sent successfully: " + audioRow.fileName);

        } catch (SQLException e) {
            System.err.println("[DB] Error fetching audio info: " + e.getMessage());
            sendFrame(Frame.error("DB_ERROR_AUDIO_FETCH"));
        } catch (IOException e) {
            System.err.println("[SERVER] Audio download failed: " + e.getMessage());
            sendFrame(Frame.error("DOWNLOAD_AUDIO_FAIL"));
        }
    }

    /* ================= DELETE AUDIO ================= */
    private void handleDeleteAudio(Frame f) {
        long msgId = parseLongSafe(f.body, 0L);
        if (msgId <= 0) {
            sendFrame(Frame.error("INVALID_AUDIO_ID"));
            return;
        }

        try {
            boolean deleted = fileDao.deleteAudioByMessageId(msgId);
            if (deleted) {
                sendFrame(Frame.ack("OK AUDIO_DELETED"));
                Frame evt = new Frame(MessageType.DELETE_AUDIO, username, "", "");
                evt.transferId = String.valueOf(msgId);
                broadcast(evt.toString(), true);
                System.out.println("[SERVER] Audio deleted for messageId=" + msgId);
            } else {
                sendFrame(Frame.error("AUDIO_NOT_FOUND"));
            }
        } catch (SQLException e) {
            System.err.println("[DB] Audio delete error: " + e.getMessage());
            sendFrame(Frame.error("DB_AUDIO_DELETE_FAIL"));
        }
    }

    
    private AudioResult receiveAudio(Frame meta) throws IOException {
        if (!UPLOAD_DIR.exists()) UPLOAD_DIR.mkdirs();

        String body = meta.body == null ? "" : meta.body;
        String audioId = jsonGet(body, "audioId");
        int duration = (int) parseLongSafe(jsonGet(body, "durationSec"), 0);
        long declaredSz = parseLongSafe(jsonGet(body, "size"), 0);

        if (audioId == null || audioId.isBlank()) audioId = java.util.UUID.randomUUID().toString();
        File out = new File(UPLOAD_DIR, sanitizeFilename(audioId));
        fileNameMap.put(audioId, "audio-" + audioId + ".aud");

        long written = 0;
        BufferedOutputStream bos = null;
        try (FileOutputStream fo = new FileOutputStream(out)) {
            bos = new BufferedOutputStream(fo);
            socket.setSoTimeout(10_000);
            int expectSeq = 0;
            while (true) {
                Frame ch = FrameIO.read(binIn);
                if (ch == null || ch.type != MessageType.AUDIO_CHUNK)
                    throw new IOException("Expected AUDIO_CHUNK");
                if (!audioId.equals(ch.transferId))
                    throw new IOException("Mismatched audioId");
                if (ch.seq != expectSeq++)
                    throw new IOException("Out-of-order chunk");

                byte[] data = (ch.bin == null) ? new byte[0] : ch.bin;
                if (written + data.length > Frame.MAX_FILE_BYTES)
                    throw new IOException("File exceeds limit");
                if (data.length > 0) {
                    bos.write(data);
                    written += data.length;
                }
                if (ch.last) break;
            }
            bos.flush();
        } finally {
            if (bos != null) {
                try { bos.close(); } catch (IOException e) {
                    System.err.println("[SERVER] Failed to close bos: " + e.getMessage());
                }
            }
            socket.setSoTimeout(0);
        }

        if (declaredSz > 0 && declaredSz != written) {
            System.out.println("[SERVER] WARN: declared=" + declaredSz + " but written=" + written);
        }

        return new AudioResult(audioId, meta.recipient, out.getName(), written, duration);
    }

    private void notifyIncomingAudio(AudioResult a) {
        if (a.to == null || a.to.isBlank()) return;
        ClientHandler target = online.get(a.to);
        if (target == null) return;

        String json = "{"
                + "\"from\":\"" + escJson(username) + "\","
                + "\"to\":\""   + escJson(a.to) + "\","
                + "\"id\":\""   + escJson(a.id) + "\","
                + "\"name\":\"" + escJson(a.savedName) + "\","
                + "\"mime\":\"audio/unknown\","
                + "\"bytes\":"  + a.bytes + ","
                + "\"duration\":" + a.durationSec
                + "}";
        Frame evt = new Frame(MessageType.AUDIO_EVT, username, a.to, json);
        target.sendFrame(evt);
    }

    /* ================== Utils ================== */
    private static String jsonGet(String json, String key) {
        if (json == null) return null;
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

    private static String pickJson(String json, String key) {
        return jsonGet(json, key);
    }

    private static String escJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        String safe = name.replaceAll("[\\r\\n\\t]", "").replaceAll("[<>:\"|?*]", "_");
        if (safe.equals(".") || safe.equals("..") || safe.isBlank()) safe = "file";
        return safe;
    }

    /* ================= CALL ================= */
    private void handleCall(Frame f) {
        final var router = CallRouter.getInstance();
        router.route(username, f);
    }

    /* ================= Helpers ================= */
    private void broadcast(String msg, boolean excludeSelf) {
        for (ClientHandler c : clients) {
            if (excludeSelf && c == this) continue;
            c.sendFrame(Frame.ack(msg));
        }
    }

    public void sendFrame(Frame f) {
        if (binOut == null) {
            System.err.println("[SERVER] Cannot send frame: binOut is null");
            return;
        }
        try {
            FrameIO.write(binOut, f);
            binOut.flush();
        } catch (Exception e) {
            System.err.println("[SERVER] Send frame failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void cleanup() {
        if (username != null) {
            CallRouter.getInstance().unregister(username, this);
            online.remove(username, this);
            broadcast("🔴 " + username + " left", true);
            username = null;
        }
        clients.remove(this);
        close();
        System.out.println("⬅ Client disconnected: " + socketSafe());
    }

    public void close() {
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }

    private String socketSafe() {
        try { return String.valueOf(socket.getRemoteSocketAddress()); }
        catch (Exception e) { return "?"; }
    }

    private static long parseLongSafe(String s, long def) {
        if (s == null || s.isBlank()) return def;
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }
}

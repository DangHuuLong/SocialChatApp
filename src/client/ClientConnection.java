package client;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.*;                 // [NEW]

import client.signaling.CallSignalingService;
import common.Frame;
import common.FrameIO;
import common.MessageType;

public class ClientConnection {
    // ===== [OLD] =====
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private CallSignalingService callService;

    private Consumer<String> onMessage;
    private Consumer<Exception> onError;

    // ===== [NEW] stream nhị phân để gửi/nhận frame khi cần =====
    private DataInputStream binIn;
    private DataOutputStream binOut;

    // ===== [NEW] giới hạn client-side =====
    public static final long MAX_FILE_BYTES = Frame.MAX_FILE_BYTES; // 25MB
    public static final int  MAX_AUDIO_SEC  = Frame.MAX_AUDIO_SECONDS; // 30s

    // ===== [NEW] quản lý ACK theo id =====
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingAcks = new ConcurrentHashMap<>();

    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);

            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();

            // [OLD]
            in  = new BufferedReader(new InputStreamReader(rawIn, "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(rawOut, "UTF-8"), true);

            // [NEW]
            binIn  = new DataInputStream(new BufferedInputStream(rawIn));
            binOut = new DataOutputStream(new BufferedOutputStream(rawOut));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void attachCallService(CallSignalingService s) { this.callService = s; }

    // [OLD]
    public synchronized void sendRaw(String line) {
        out.println(line);
        out.flush();
    }

    // ===== [OLD] GỬI FILE: Text→Frames (không chờ ACK) =====
    public synchronized void sendFile(String from, String to, File file, String mimeOrNull) throws IOException {
        if (file == null || !file.exists()) throw new FileNotFoundException("File not found");
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File too large (>25MB)");
        if (binOut == null) throw new IOException("Connection not ready");

        // 0) báo server chuyển sang đọc nhị phân
        send("SEND_FILE");

        String mime = (mimeOrNull != null && !mimeOrNull.isBlank())
                ? mimeOrNull
                : guessMime(file);

        String fileId = UUID.randomUUID().toString();

        // 1) META
        Frame meta = Frame.fileMeta(from, to, file.getName(), mime, fileId, file.length());
        FrameIO.write(binOut, meta);

        // 2) CHUNKs
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[Frame.CHUNK_SIZE];
            int n, seq = 0;
            while ((n = fis.read(buf)) != -1) {
                boolean last = (fis.available() == 0);
                byte[] slice = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                Frame ch = Frame.fileChunk(from, to, fileId, seq++, last, slice);
                FrameIO.write(binOut, ch);
            }
        }
    }

    // ===== [NEW] GỬI FILE + CHỜ ACK (dễ test) =====
    public synchronized String sendFileWithAck(String from, String to, File file, String mimeOrNull, long timeoutMs)
            throws IOException, InterruptedException, TimeoutException {
        if (file == null || !file.exists()) throw new FileNotFoundException("File not found");
        if (file.length() > MAX_FILE_BYTES) throw new IOException("File too large (>25MB)");
        if (binOut == null) throw new IOException("Connection not ready");

        send("SEND_FILE");

        String mime = (mimeOrNull != null && !mimeOrNull.isBlank()) ? mimeOrNull : guessMime(file);
        String fileId = UUID.randomUUID().toString();

        // Đăng ký future đợi ACK cho fileId này
        CompletableFuture<String> fut = new CompletableFuture<>();
        pendingAcks.put(fileId, fut);

        // META
        Frame meta = Frame.fileMeta(from, to, file.getName(), mime, fileId, file.length());
        FrameIO.write(binOut, meta);

        // CHUNKs
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buf = new byte[Frame.CHUNK_SIZE];
            int n, seq = 0;
            while ((n = fis.read(buf)) != -1) {
                boolean last = (fis.available() == 0);
                byte[] slice = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                Frame ch = Frame.fileChunk(from, to, fileId, seq++, last, slice);
                FrameIO.write(binOut, ch);
            }
        }

        // Chờ ACK: "OK FILE_SAVED <fileId> <bytes> <name>"
        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Failed waiting ACK", e.getCause());
        } catch (TimeoutException te) {
            pendingAcks.remove(fileId);
            throw te;
        }
    }

    private static String guessMime(File f) {
        try {
            String m = Files.probeContentType(f.toPath());
            return (m != null) ? m : "application/octet-stream";
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    // ===== [OLD] GỬI AUDIO (không chờ ACK) =====
    public synchronized void sendAudio(String from, String to, byte[] audioBytes,
                                       String codec, int sampleRate, int durationSec) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
        if (durationSec > MAX_AUDIO_SEC) throw new IOException("Audio too long (>30s)");
        if (binOut == null) throw new IOException("Connection not ready");

        send("SEND_AUDIO"); // báo server

        String audioId = UUID.randomUUID().toString();

        // 1) META
        Frame meta = Frame.audioMeta(from, to, codec, sampleRate, durationSec, audioId, audioBytes.length);
        FrameIO.write(binOut, meta);

        // 2) CHUNKs
        int off = 0, seq = 0;
        while (off < audioBytes.length) {
            int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
            boolean last = (off + len) >= audioBytes.length;
            byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
            Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
            FrameIO.write(binOut, ch);
            off += len;
        }
    }

    // ===== [NEW] GỬI AUDIO + CHỜ ACK (tuỳ chọn) =====
    public synchronized String sendAudioWithAck(String from, String to, byte[] audioBytes,
                                                String codec, int sampleRate, int durationSec, long timeoutMs)
            throws IOException, InterruptedException, TimeoutException {
        if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
        if (durationSec > MAX_AUDIO_SEC) throw new IOException("Audio too long (>30s)");
        if (binOut == null) throw new IOException("Connection not ready");

        send("SEND_AUDIO");

        String audioId = UUID.randomUUID().toString();
        CompletableFuture<String> fut = new CompletableFuture<>();
        pendingAcks.put(audioId, fut);

        Frame meta = Frame.audioMeta(from, to, codec, sampleRate, durationSec, audioId, audioBytes.length);
        FrameIO.write(binOut, meta);

        int off = 0, seq = 0;
        while (off < audioBytes.length) {
            int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
            boolean last = (off + len) >= audioBytes.length;
            byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
            Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
            FrameIO.write(binOut, ch);
            off += len;
        }

        try {
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IOException("Failed waiting ACK", e.getCause());
        } catch (TimeoutException te) {
            pendingAcks.remove(audioId);
            throw te;
        }
    }

    // ===== [OLD] listener text =====
    public void startListener(Consumer<String> onMessage, Consumer<Exception> onError) {
        this.onMessage = onMessage;
        this.onError   = onError;

        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isEmpty()) continue;

                    if (callService != null && line.startsWith("CALL_")) {
                        if (callService.parseIncoming(line)) {
                            continue;
                        }
                    }

                    // ===== [NEW] Bắt tín hiệu ACK từ server =====
                    // Format khuyến nghị server: 
                    //  OK FILE_SAVED <fileId> <bytes> <name>
                    //  OK AUDIO_SAVED <audioId> <bytes>
                    if (line.startsWith("OK FILE_SAVED")) {
                        String[] p = line.split("\\s+", 5);
                        if (p.length >= 3) {
                            String fileId = p[2];
                            CompletableFuture<String> fut = pendingAcks.remove(fileId);
                            if (fut != null) fut.complete(line);
                        }
                    } else if (line.startsWith("OK AUDIO_SAVED")) {
                        String[] p = line.split("\\s+", 4);
                        if (p.length >= 3) {
                            String audioId = p[2];
                            CompletableFuture<String> fut = pendingAcks.remove(audioId);
                            if (fut != null) fut.complete(line);
                        }
                    }
                    // =============================================

                    if (onMessage != null) onMessage.accept(line);
                }
                if (onError != null) onError.accept(new EOFException("Server closed connection"));
            } catch (IOException e) {
                if (onError != null) onError.accept(e);
            }
        }, "server-listener");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // ===== [OLD] lệnh text =====
    public void login(String username) { send("LOGIN " + username); }
    public void sendMessageAll(String text) { send("MSG " + text); }
    public void sendDirectMessage(String to, String text) { send("DM " + to + " " + text); }
    public void quit() { send("QUIT"); }

    public synchronized void send(String raw) {
        if (out != null) out.println(raw);
    }

    public boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
    }
}

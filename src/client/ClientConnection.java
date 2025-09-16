package client;

import client.controller.MidController;
import client.signaling.CallSignalingService;
import common.Frame;
import common.FrameIO;
import common.MessageType;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.application.Platform;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioSystem;

public class ClientConnection {
    private Socket socket;
    private DataInputStream binIn;
    private DataOutputStream binOut;
    private Thread readerThread;
    private CallSignalingService callService;
    private Consumer<Frame> onFrame;
    private Consumer<Exception> onError;
    private final ConcurrentHashMap<String, CompletableFuture<Frame>> pendingAcks = new ConcurrentHashMap<>();
    private MidController midController;

    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setTcpNoDelay(true);

            InputStream rawIn = socket.getInputStream();
            OutputStream rawOut = socket.getOutputStream();
            binIn = new DataInputStream(new BufferedInputStream(rawIn));
            binOut = new DataOutputStream(new BufferedOutputStream(rawOut));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try { if (binIn != null) binIn.close(); } catch (Exception ignored) {}
        try { if (binOut != null) binOut.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (Exception ignored) {}
        if (readerThread != null && readerThread.isAlive()) readerThread.interrupt();
    }

    public void attachCallService(CallSignalingService s) { this.callService = s; }
    public void setMidController(MidController controller) {
        this.midController = controller;
    }
    
    public void downloadFile(String fileId) throws IOException {
        // server DOWNLOAD_FILE đọc f.body = fileId
        Frame req = new Frame(MessageType.DOWNLOAD_FILE, "", "", fileId);
        sendFrame(req);
    }


    public void startListener(Consumer<Frame> onFrame, Consumer<Exception> onError) {
        this.onFrame = onFrame;
        this.onError = onError;

        readerThread = new Thread(() -> {
            try {
                while (true) {
                    Frame f = FrameIO.read(binIn);
                    if (f == null) break;

                    if (callService != null && callService.tryHandleIncoming(f)) {
                        continue;
                    }

                    System.out.println("[NET] RECV type=" + f.type + " transferId=" + f.transferId + " body=" + f.body);

                    if (f.type == MessageType.ACK && f.transferId != null && !f.transferId.isEmpty()) {
                        CompletableFuture<Frame> fut = pendingAcks.remove(f.transferId);
                        if (fut != null) {
                            fut.complete(f);
                            continue;
                        }
                    }

                    if (f.type == MessageType.ERROR && f.transferId != null && !f.transferId.isEmpty()) {
                        CompletableFuture<Frame> fut = pendingAcks.remove(f.transferId);
                        if (fut != null) {
                            fut.completeExceptionally(new IOException(f.body));
                        }
                        if (midController != null) {
                        }
                        continue;
                    }

                    if (this.onFrame != null) {
                        this.onFrame.accept(f);
                    }
                }
                if (this.onError != null) this.onError.accept(new EOFException("Server closed connection"));
            } catch (IOException e) {
                if (this.onError != null) this.onError.accept(e);
            }
        }, "server-listener");

        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void loginFrame(String username) throws IOException {
        sendFrame(new Frame(MessageType.LOGIN, username, "", ""));
    }

    public synchronized void sendFrame(Frame f) throws IOException {
        System.out.println("[DEBUG] sendFrame: type=" + f.type + ", transferId=" + f.transferId);
        FrameIO.write(binOut, f);
    }

    public void register(String username) throws IOException {
        sendFrame(Frame.register(username));
    }

    public void dm(String from, String to, String text) throws IOException {
        sendFrame(Frame.dm(from, to, text));
    }

    public void history(String from, String peer, int limit) throws IOException {
        Frame f = new Frame(MessageType.HISTORY, from, peer, String.valueOf(limit));
        sendFrame(f);
    }

    public synchronized Frame sendFileWithAck(String from, String to, File file, String mimeOrNull, String fileId, long timeoutMs)
            throws Exception {
        int retries = 3;
        Exception lastEx = null;

        for (int attempt = 0; attempt < retries; attempt++) {
            try {
                ensureSendableFile(file);

                // mime + duration (dùng để hiển thị)
                String mime = (mimeOrNull != null && !mimeOrNull.isBlank()) ? mimeOrNull : guessMime(file);
                String durationVal = "--:--";
                if (mime != null && (mime.equals("audio/wav") || mime.equals("audio/x-wav")
                                  || mime.equals("audio/aiff") || mime.equals("audio/x-aiff"))) {
                    try {
                        var aff = javax.sound.sampled.AudioSystem.getAudioFileFormat(file);
                        long frames = aff.getFrameLength();
                        float frameRate = aff.getFormat().getFrameRate();
                        if (frames > 0 && frameRate > 0) {
                            int sec = (int)((frames / frameRate));
                            durationVal = String.format("%d:%02d", sec/60, sec%60);
                        }
                    } catch (Exception ignored) { /* im lặng */ }
                }

                final String fFrom = from;
                final String fTo = to;
                final String fFileId = fileId;
                final String fMime = (mime == null ? "application/octet-stream" : mime);
                final String fName = file.getName();
                final long   fSize = file.length();
                final String fDuration = durationVal;
                final String localUrl = file.toURI().toString(); // file:///...

                // Vẽ bubble người gửi trên FX thread (không có nhãn “Sending…”)
                if (midController != null) {
                    javafx.application.Platform.runLater(() -> {
                        try { midController.showOutgoingFile(fName, fMime, fSize, fFileId, fDuration); }
                        catch (Exception uiEx) { System.err.println("[UI] showOutgoingFile failed: " + uiEx.getMessage()); }
                    });
                }

                // chuẩn bị ACK future
                CompletableFuture<Frame> fut = new CompletableFuture<>();
                pendingAcks.put(fFileId, fut);

                // Gửi META
                Frame meta = Frame.fileMeta(fFrom, fTo, fName, fMime, fFileId, fSize);
                sendFrame(meta);

                // Gửi CHUNK
                try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                    byte[] buf = new byte[Frame.CHUNK_SIZE];
                    int n, seq = 0;
                    long rem = fSize;
                    while ((n = fis.read(buf)) != -1) {
                        rem -= n;
                        boolean last = (rem == 0);
                        byte[] slice = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                        Frame ch = Frame.fileChunk(fFrom, fTo, fFileId, seq++, last, slice);
                        sendFrame(ch);
                    }
                }

                // Chờ ACK
             // ClientConnection.sendFileWithAck(...)
                Frame ack = fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);

                // Sau ACK: gắn media cho bubble người gửi
                if (midController != null) {
                    javafx.application.Platform.runLater(() -> {
                        try {
                            HBox row = midController.outgoingFileBubbles.get(fFileId);
                            if (row != null) {
                                boolean isAudio = fMime.startsWith("audio/");
                                boolean isVideo = fMime.startsWith("video/");
                                boolean isImage = fMime.startsWith("image/");
                                if (isAudio) {
                                    midController.updateVoiceBubbleFromUrl(row, localUrl);
                                } else if (isVideo) {
                                    midController.updateVideoBubbleFromUrl(row, localUrl);
                                } else if (isImage) {
                                    midController.updateImageBubbleFromUrl(row, localUrl);
                                }
                            }
                        } catch (Exception uiEx) {
                            System.err.println("[UI] finalize bubble failed: " + uiEx.getMessage());
                        }
                    });
                }


                return ack;

            } catch (java.util.concurrent.TimeoutException te) {
                pendingAcks.remove(fileId);
                lastEx = te;
                System.err.println("[RETRY] Attempt " + (attempt + 1) + " timed out");
                Thread.sleep(1000);
            } catch (IOException ioex) {
                lastEx = ioex;
                System.err.println("[RETRY] Attempt " + (attempt + 1) + " failed: " + ioex.getMessage());
                Thread.sleep(1000);
            }
        }

        throw lastEx != null ? lastEx : new IOException("Failed to send file after " + retries + " attempts");
    }



    public synchronized void sendAudio(String from, String to, byte[] audioBytes, String codec, int sampleRate, int durationSec)
            throws IOException {
        if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
        if (durationSec > Frame.MAX_AUDIO_SECONDS) throw new IOException("Audio too long (>30s)");

        String audioId = UUID.randomUUID().toString();

        Frame meta = Frame.audioMeta(from, to, codec, sampleRate, durationSec, audioId, audioBytes.length);
        sendFrame(meta);

        int off = 0, seq = 0;
        while (off < audioBytes.length) {
            int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
            boolean last = (off + len) >= audioBytes.length;
            byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
            Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
            sendFrame(ch);
            off += len;
        }
    }

    public synchronized Frame sendAudioWithAck(String from, String to, byte[] audioBytes, String codec, int sampleRate, int durationSec, long timeoutMs)
            throws Exception {
        int retries = 3;
        Exception lastEx = null;
        String audioId = null;
        for (int i = 0; i < retries; i++) {
            try {
                if (audioBytes == null || audioBytes.length == 0) throw new IOException("Empty audio");
                if (durationSec > Frame.MAX_AUDIO_SECONDS) throw new IOException("Audio too long (>30s)");

                audioId = UUID.randomUUID().toString();
                CompletableFuture<Frame> fut = new CompletableFuture<>();
                pendingAcks.put(audioId, fut);

                Frame meta = Frame.audioMeta(from, to, codec, sampleRate, durationSec, audioId, audioBytes.length);
                sendFrame(meta);

                int off = 0, seq = 0;
                while (off < audioBytes.length) {
                    int len = Math.min(Frame.CHUNK_SIZE, audioBytes.length - off);
                    boolean last = (off + len) >= audioBytes.length;
                    byte[] slice = java.util.Arrays.copyOfRange(audioBytes, off, off + len);
                    Frame ch = Frame.audioChunk(from, to, audioId, seq++, last, slice);
                    sendFrame(ch);
                    off += len;
                }

                Frame ack = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (midController != null) {
                }
                return ack;
            } catch (IOException e) {
                lastEx = e;
                System.err.println("[RETRY] Attempt " + (i + 1) + " failed: " + e.getMessage());
                Thread.sleep(1000);
                continue;
            } catch (TimeoutException te) {
                pendingAcks.remove(audioId);
                if (midController != null) {
                }
                throw te;
            }
        }
        if (midController != null) {
        }
        throw lastEx != null ? lastEx : new IOException("Failed to send audio after " + retries + " attempts");
    }

    private static void ensureSendableFile(File file) throws IOException {
        if (file == null || !file.exists()) throw new FileNotFoundException("File not found");
        if (file.length() > Frame.MAX_FILE_BYTES) throw new IOException("File too large (>25MB)");
    }

    public static String guessMime(File f) {
        try {
            String m = Files.probeContentType(f.toPath());
            if (m != null && !m.isBlank()) {
                System.out.println("[DEBUG] Mime from probe: " + m);
                return m;
            }
        } catch (IOException e) {
            System.err.println("[DEBUG] Probe failed: " + e.getMessage());
        }
        String name = f.getName().toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg") ||
            name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
            return "image/" + name.substring(name.lastIndexOf('.') + 1);
        }
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".m4a") || name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".mp4") || name.endsWith(".mov")) return "video/mp4";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".webm")) return "video/webm";
        System.out.println("[DEBUG] Fallback mime: application/octet-stream");
        return "application/octet-stream";
    }
}
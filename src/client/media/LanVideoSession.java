package client.media;

import com.github.sarxos.webcam.Webcam;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Objects;

public class LanVideoSession {

    // ======= cấu hình gửi/nhận =======
    private int width = 640;
    private int height = 480;
    private int fps = 12;
    private float jpegQuality = 0.70f;

    // ======= mock / chọn camera theo tên qua VM args =======
    // Bật mock cho instance phụ: -Dlan.mockcam=true
    private final boolean MOCK = Boolean.getBoolean("lan.mockcam");
    // Tuỳ chọn text overlay: -Dlan.mockcam.text="Client B"
    private final String MOCK_TEXT = System.getProperty("lan.mockcam.text", "MOCK");
    // Tuỳ chọn fps cho mock: -Dlan.mockcam.fps=10
    private final int MOCK_FPS = Integer.getInteger("lan.mockcam.fps", fps);
    // Chọn camera theo tên: -Dlan.camera.name="OBS Virtual Camera"
    private final String CAMERA_NAME = System.getProperty("lan.camera.name", "");

    // ======= trạng thái =======
    private volatile boolean running = false;

    // network
    private ServerSocket serverSocket;   // caller side
    private Socket socket;               // both sides
    private Thread acceptThread;
    private Thread sendThread;
    private Thread recvThread;

    // video
    private Webcam webcam;
    private ImageView localView;
    private ImageView remoteView;
    private BufferedImage mockFrame; // bộ đệm cho mock

    // ======= OFFER JSON =======
    public static class OfferInfo {
        public final String host;
        public final int port;
        public OfferInfo(String host, int port) { this.host = host; this.port = port; }
        public String toJson() { return "{\"host\":\"" + host + "\",\"port\":" + port + "}"; }
        public static OfferInfo fromJson(String json) throws IOException {
            String s = json.trim();
            String host = null; int port = -1;
            int hi = s.indexOf("\"host\"");
            if (hi >= 0) {
                int c = s.indexOf(':', hi);
                int q1 = s.indexOf('"', c + 1);
                int q2 = s.indexOf('"', q1 + 1);
                host = s.substring(q1 + 1, q2);
            }
            int pi = s.indexOf("\"port\"");
            if (pi >= 0) {
                int c = s.indexOf(':', pi);
                int e = c + 1;
                while (e < s.length() && Character.isWhitespace(s.charAt(e))) e++;
                int e2 = e;
                while (e2 < s.length() && Character.isDigit(s.charAt(e2))) e2++;
                port = Integer.parseInt(s.substring(e, e2));
            }
            if (host == null || port <= 0) throw new IOException("Bad OFFER JSON");
            return new OfferInfo(host, port);
        }
    }

    // ===== caller chuẩn bị host:port =====
    public OfferInfo prepareCaller() throws IOException {
        String host = detectLanAddress();
        serverSocket = new ServerSocket(0, 1, InetAddress.getByName(host));
        System.out.println("[MEDIA] prepareCaller -> " + host + ":" + serverSocket.getLocalPort());
        return new OfferInfo(host, serverSocket.getLocalPort());
    }

    // ===== caller chờ callee connect =====
    public void startAsCaller(ImageView local, ImageView remote) throws IOException {
        ensureNotRunning();
        this.localView = Objects.requireNonNull(local);
        this.remoteView = Objects.requireNonNull(remote);
        running = true;

        System.out.println("[MEDIA] startAsCaller: waiting on " + serverSocket.getLocalSocketAddress());
        acceptThread = new Thread(() -> {
            try {
                socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                System.out.println("[MEDIA] caller: peer connected " + socket.getRemoteSocketAddress());
                serverSocket.close(); serverSocket = null;
                startStreamingThreads();
            } catch (IOException e) {
                System.out.println("[MEDIA] caller: accept error " + e);
                stop();
            }
        }, "lan-video-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    // ===== callee kết nối tới caller =====
    public void startAsCallee(String host, int port, ImageView local, ImageView remote) throws IOException {
        ensureNotRunning();
        this.localView = Objects.requireNonNull(local);
        this.remoteView = Objects.requireNonNull(remote);
        running = true;

        System.out.println("[MEDIA] startAsCallee: connecting to " + host + ":" + port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5_000);
        socket.setTcpNoDelay(true);
        System.out.println("[MEDIA] callee: connected, local=" + socket.getLocalSocketAddress());
        startStreamingThreads();
    }

    // ===== lifecycle =====
    public synchronized void stop() {
        System.out.println("[MEDIA] stop() called");
        running = false;

        if (socket != null) {
            try { socket.close(); } catch (Exception ignore) {}
            socket = null;
        }
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignore) {}
            serverSocket = null;
        }
        if (webcam != null) {
            try { webcam.close(); System.out.println("[MEDIA] webcam closed"); } catch (Exception ignore) {}
            webcam = null;
        }
    }

    private void startStreamingThreads() throws IOException {
        System.out.println("[MEDIA] startStreamingThreads");

        boolean canSend = false;

        if (MOCK) {
            // mock sender: không đụng webcam thật
            System.out.println("[MEDIA] MOCK camera enabled (text=" + MOCK_TEXT + ")");
            if (MOCK_FPS > 0) this.fps = MOCK_FPS;
            canSend = true;
        } else {
            // mở webcam thật (hoặc theo tên)
            try {
                if (CAMERA_NAME != null && !CAMERA_NAME.isBlank()) {
                    for (Webcam w : Webcam.getWebcams()) {
                        if (w.getName().toLowerCase().contains(CAMERA_NAME.toLowerCase())) {
                            webcam = w; break;
                        }
                    }
                }
                if (webcam == null) webcam = Webcam.getDefault();

                if (webcam != null) {
                    webcam.setViewSize(new Dimension(width, height));
                    webcam.open(true);
                    System.out.println("[MEDIA] webcam opened " + width + "x" + height + " @ " + fps + "fps");
                    canSend = true;
                } else {
                    System.out.println("[MEDIA] no webcam detected -> receive-only");
                }
            } catch (Throwable ex) {
                // webcam đang bị khoá hoặc lỗi driver → nhận-only
                System.out.println("[MEDIA] open webcam failed -> receive-only: " + ex);
                webcam = null;
                canSend = false;
            }
        }

        // chỉ start sender khi có nguồn khung (mock hoặc webcam)
        if (canSend) {
            sendThread = new Thread(this::loopSendFrames, "lan-video-send");
            sendThread.setDaemon(true);
            sendThread.start();
        } else {
            System.out.println("[MEDIA] sender disabled (no webcam)");
        }

        // luôn start receiver để xem video từ đối tác
        recvThread = new Thread(this::loopRecvFrames, "lan-video-recv");
        recvThread.setDaemon(true);
        recvThread.start();
    }

    private void loopSendFrames() {
        System.out.println("[MEDIA] send loop started");
        try {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            long frameIntervalNanos = 1_000_000_000L / Math.max(1, fps);
            long lastTick = System.currentTimeMillis();
            int sent = 0;
            int lastBytes = 0;

            while (running && socket != null && socket.isConnected()) {
                long t0 = System.nanoTime();

                BufferedImage bi = grabFrame(); // lấy khung từ webcam hoặc mock
                if (bi != null) {
                    // preview local
                    Image fx = SwingFXUtils.toFXImage(bi, null);
                    Platform.runLater(() -> localView.setImage(fx));

                    byte[] jpg = encodeJpeg(bi, jpegQuality);
                    lastBytes = jpg.length;

                    byte[] len = ByteBuffer.allocate(4).putInt(jpg.length).array();
                    out.write(len);
                    out.write(jpg);
                    out.flush();
                    sent++;
                }

                long elapsed = System.nanoTime() - t0;
                long remain = frameIntervalNanos - elapsed;
                if (remain > 0) {
                    try { Thread.sleep(remain / 1_000_000L, (int) (remain % 1_000_000L)); } catch (InterruptedException ignore) {}
                }

                long now = System.currentTimeMillis();
                if (now - lastTick >= 1000) {
                    System.out.println("[MEDIA] send ~" + sent + " fps, lastFrame=" + lastBytes + "B");
                    sent = 0; lastTick = now;
                }
            }
        } catch (IOException e) {
            System.out.println("[MEDIA] send loop error: " + e);
        } finally {
            stop();
        }
    }

    private void loopRecvFrames() {
        System.out.println("[MEDIA] recv loop started");
        try {
            InputStream in = new BufferedInputStream(socket.getInputStream());
            byte[] lenBuf = new byte[4];
            long lastTick = System.currentTimeMillis();
            int got = 0;
            int lastBytes = 0;

            while (running && socket != null && socket.isConnected()) {
                if (!readFully(in, lenBuf, 0, 4)) {
                    System.out.println("[MEDIA] recv: EOF length");
                    break;
                }
                int len = ByteBuffer.wrap(lenBuf).getInt();
                if (len <= 0 || len > (10 * 1024 * 1024)) {
                    System.out.println("[MEDIA] recv: invalid frame len=" + len);
                    break;
                }

                byte[] body = new byte[len];
                if (!readFully(in, body, 0, len)) {
                    System.out.println("[MEDIA] recv: EOF body");
                    break;
                }
                lastBytes = len;

                try {
                    BufferedImage bi = ImageIO.read(new ByteArrayInputStream(body));
                    if (bi != null) {
                        Image fx = SwingFXUtils.toFXImage(bi, null);
                        Platform.runLater(() -> {
                            remoteView.setImage(fx);
                            var b = remoteView.getBoundsInParent();
                            System.out.println("[UI] remoteView size = " + b.getWidth() + "x" + b.getHeight());
                        });
                    }
                } catch (IOException ex) {
                    System.out.println("[MEDIA] decode error: " + ex);
                }

                got++;

                long now = System.currentTimeMillis();
                if (now - lastTick >= 1000) {
                    System.out.println("[MEDIA] recv ~" + got + " fps, lastFrame=" + lastBytes + "B");
                    got = 0; lastTick = now;
                }
            }
        } catch (IOException e) {
            System.out.println("[MEDIA] recv loop error: " + e);
        } finally {
            stop();
        }
    }

    // ===== helpers =====
    private BufferedImage grabFrame() {
        if (MOCK) {
            if (mockFrame == null)
                mockFrame = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
            var g = mockFrame.createGraphics();
            g.setColor(java.awt.Color.DARK_GRAY);
            g.fillRect(0,0,width,height);
            g.setColor(java.awt.Color.WHITE);
            g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 28));
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS").format(new java.util.Date());
            g.drawString(MOCK_TEXT + "  " + ts, 20, 40);
            g.dispose();
            return mockFrame;
        }
        return (webcam != null) ? webcam.getImage() : null;
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int need = len, n;
        while (len > 0 && (n = in.read(b, off, len)) != -1) { off += n; len -= n; }
        boolean ok = (len == 0);
        if (!ok) System.out.println("[MEDIA] readFully: short read, need=" + need);
        return ok;
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpg");
        if (!it.hasNext()) throw new IOException("No JPEG writer");
        ImageWriter writer = it.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            writer.write(null, new IIOImage(img, null, null), param);
            return baos.toByteArray();
        } finally {
            try { writer.dispose(); } catch (Exception ignore) {}
        }
    }

    private static String detectLanAddress() throws SocketException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 53);
            String ip = s.getLocalAddress().getHostAddress();
            System.out.println("[MEDIA] detectLanAddress via UDP -> " + ip);
            return ip;
        } catch (Exception ignore) {
            for (NetworkInterface nif : java.util.Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress addr : java.util.Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        System.out.println("[MEDIA] detectLanAddress fallback -> " + addr.getHostAddress());
                        return addr.getHostAddress();
                    }
                }
            }
            System.out.println("[MEDIA] detectLanAddress fallback -> 127.0.0.1");
            return "127.0.0.1";
        }
    }

    private void ensureNotRunning() throws IOException {
        if (running) throw new IOException("Session already running");
    }
}

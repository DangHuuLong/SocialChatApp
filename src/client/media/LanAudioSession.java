package client.media;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.Objects;

/** Audio 1-1 qua LAN bằng TCP, PCM 16-bit mono. */
public class LanAudioSession {

    // cấu hình âm thanh
    private final float  SAMPLE_RATE = 16000f;           // 16 kHz
    private final int    FRAME_MS    = 20;               // 20ms / khung
    private final int    BYTES_PER_S = (int) (SAMPLE_RATE * 2); // 2 bytes/sample (16-bit)
    private final int    FRAME_BYTES = BYTES_PER_S * FRAME_MS / 1000;

    // mock mic khi test 2 client trên cùng máy
    private final boolean MOCK_MIC = Boolean.getBoolean("lan.mockmic");
    private final double  TONE_HZ  = Double.parseDouble(System.getProperty("lan.mockmic.hz", "440"));

    private volatile boolean running = false;

    private ServerSocket serverSock;   // caller
    private Socket       socket;       // cả 2 phía
    private Thread       acceptThread, sendThread, recvThread;

    // lines
    private TargetDataLine  mic;
    private SourceDataLine  speaker;

    // ===== caller chuẩn bị port audio (trên host đã biết) =====
    public int prepareCaller(String host) throws IOException {
        serverSock = new ServerSocket(0, 1, InetAddress.getByName(host));
        System.out.println("[AUDIO] prepareCaller -> " + host + ":" + serverSock.getLocalPort());
        return serverSock.getLocalPort();
    }

    // ===== caller chờ callee connect =====
    public void startAsCaller() throws IOException {
        ensureNotRunning();
        running = true;
        System.out.println("[AUDIO] startAsCaller: waiting on " + serverSock.getLocalSocketAddress());
        acceptThread = new Thread(() -> {
            try {
                socket = serverSock.accept();
                System.out.println("[AUDIO] caller: peer connected " + socket.getRemoteSocketAddress());
                serverSock.close(); serverSock = null;
                startStreamingThreads();
            } catch (IOException e) {
                System.out.println("[AUDIO] caller accept error: " + e);
                stop();
            }
        }, "lan-audio-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    // ===== callee kết nối tới caller =====
    public void startAsCallee(String host, int port) throws IOException {
        ensureNotRunning();
        running = true;
        System.out.println("[AUDIO] startAsCallee: connecting " + host + ":" + port);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        System.out.println("[AUDIO] callee: connected, local=" + socket.getLocalSocketAddress());
        startStreamingThreads();
    }

    // ===== lifecycle =====
    public synchronized void stop() {
        running = false;
        if (socket != null) { try { socket.close(); } catch (Exception ignore) {} socket = null; }
        if (serverSock != null) { try { serverSock.close(); } catch (Exception ignore) {} serverSock = null; }
        if (mic != null) { try { mic.stop(); mic.close(); } catch (Exception ignore) {} mic = null; }
        if (speaker != null) { try { speaker.drain(); speaker.stop(); speaker.close(); } catch (Exception ignore) {} speaker = null; }
        System.out.println("[AUDIO] stop()");
    }

    private void startStreamingThreads() {
        try {
            // mở loa luôn
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info sInfo = new DataLine.Info(SourceDataLine.class, fmt);
            speaker = (SourceDataLine) AudioSystem.getLine(sInfo);
            speaker.open(fmt); speaker.start();

            // mic có thể mở, nếu không thì gửi silence
            if (!MOCK_MIC) {
                try {
                    DataLine.Info mInfo = new DataLine.Info(TargetDataLine.class, fmt);
                    mic = (TargetDataLine) AudioSystem.getLine(mInfo);
                    mic.open(fmt); mic.start();
                    System.out.println("[AUDIO] mic opened");
                } catch (Throwable ex) {
                    System.out.println("[AUDIO] cannot open mic -> send silence. reason=" + ex);
                    mic = null;
                }
            } else {
                System.out.println("[AUDIO] MOCK mic enabled (" + TONE_HZ + "Hz)");
            }

            sendThread = new Thread(this::loopSend, "lan-audio-send");
            recvThread = new Thread(this::loopRecv, "lan-audio-recv");
            sendThread.setDaemon(true); recvThread.setDaemon(true);
            sendThread.start(); recvThread.start();
        } catch (Exception e) {
            System.out.println("[AUDIO] start error: " + e);
            stop();
        }
    }

    private void loopSend() {
        try {
            OutputStream out = new BufferedOutputStream(Objects.requireNonNull(socket).getOutputStream());
            byte[] buf = new byte[FRAME_BYTES];
            double phase = 0.0, step = 2 * Math.PI * TONE_HZ / SAMPLE_RATE;

            while (running && socket.isConnected()) {
                int n;
                if (MOCK_MIC || mic == null) {
                    // tạo sóng sin 16-bit
                    for (int i = 0; i < FRAME_BYTES; i += 2) {
                        short s = (short) (Math.sin(phase) * 12000);
                        buf[i]   = (byte) (s & 0xFF);
                        buf[i+1] = (byte) ((s >> 8) & 0xFF);
                        phase += step;
                        if (phase > 2*Math.PI) phase -= 2*Math.PI;
                    }
                    n = FRAME_BYTES;
                } else {
                    n = mic.read(buf, 0, FRAME_BYTES);
                    if (n <= 0) continue;
                }
                // length-prefix
                out.write((n >>> 24) & 0xFF);
                out.write((n >>> 16) & 0xFF);
                out.write((n >>>  8) & 0xFF);
                out.write( n         & 0xFF);
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException e) {
            System.out.println("[AUDIO] send error: " + e);
        } finally {
            stop();
        }
    }

    private void loopRecv() {
        try {
            InputStream in = new BufferedInputStream(Objects.requireNonNull(socket).getInputStream());
            byte[] len = new byte[4];
            byte[] buf = new byte[FRAME_BYTES * 2];

            while (running && socket.isConnected()) {
                if (!readFully(in, len, 0, 4)) break;
                int n = ((len[0] & 0xFF) << 24) | ((len[1] & 0xFF) << 16) | ((len[2] & 0xFF) << 8) | (len[3] & 0xFF);
                if (n <= 0 || n > buf.length) break;
                if (!readFully(in, buf, 0, n)) break;
                if (speaker != null) speaker.write(buf, 0, n);
            }
        } catch (IOException e) {
            System.out.println("[AUDIO] recv error: " + e);
        } finally {
            stop();
        }
    }

    private static boolean readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int need = len;
        int n;
        while (len > 0 && (n = in.read(b, off, len)) != -1) {
            off += n;
            len -= n;
        }
        if (len == 0) return true;           
        System.out.println("[AUDIO] short read need=" + need);
        return false;                        
    }


    private void ensureNotRunning() throws IOException {
        if (running) throw new IOException("Audio already running");
    }
}

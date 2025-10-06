package client.media;

import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.util.Objects;

public class LanAudioSession {

    private final float SAMPLE_RATE = 16000f;
    private final int FRAME_MS = 20;
    private final int BYTES_PER_S = (int) (SAMPLE_RATE * 2);
    private final int FRAME_BYTES = BYTES_PER_S * FRAME_MS / 1000;

    private final boolean MOCK_MIC = Boolean.getBoolean("lan.mockmic");
    private final double TONE_HZ = Double.parseDouble(System.getProperty("lan.mockmic.hz", "440"));

    private static final int CTL_MUTE_ON = -1;
    private static final int CTL_MUTE_OFF = -2;

    private volatile boolean running = false;

    private ServerSocket serverSock;
    private Socket socket;
    private Thread acceptThread, sendThread, recvThread;

    private TargetDataLine mic;
    private SourceDataLine speaker;

    private volatile boolean audioEnabled = true;
    private volatile boolean peerMuted = false;
    private volatile Integer pendingCtl = null;

    public int prepareCaller(String host) throws IOException {
        serverSock = new ServerSocket(0, 1, InetAddress.getByName(host));
        return serverSock.getLocalPort();
    }

    public void startAsCaller() throws IOException {
        ensureNotRunning();
        running = true;
        acceptThread = new Thread(() -> {
            try {
                socket = serverSock.accept();
                serverSock.close();
                serverSock = null;
                socket.setTcpNoDelay(true);
                socket.setReceiveBufferSize(FRAME_BYTES * 2);
                socket.setSendBufferSize(FRAME_BYTES * 2);
                startStreamingThreads();
            } catch (IOException e) {
                stop();
            }
        }, "lan-audio-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void startAsCallee(String host, int port) throws IOException {
        ensureNotRunning();
        running = true;
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setReceiveBufferSize(FRAME_BYTES * 2);
        socket.setSendBufferSize(FRAME_BYTES * 2);
        startStreamingThreads();
    }

    public synchronized void stop() {
        running = false;
        if (socket != null) { try { socket.close(); } catch (Exception ignore) {} socket = null; }
        if (serverSock != null) { try { serverSock.close(); } catch (Exception ignore) {} serverSock = null; }
        if (mic != null) { try { mic.stop(); mic.close(); } catch (Exception ignore) {} mic = null; }
        if (speaker != null) { try { speaker.drain(); speaker.stop(); speaker.close(); } catch (Exception ignore) {} speaker = null; }
    }

    private void startStreamingThreads() {
        try {
            AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            DataLine.Info sInfo = new DataLine.Info(SourceDataLine.class, fmt);
            speaker = (SourceDataLine) AudioSystem.getLine(sInfo);
            speaker.open(fmt, FRAME_BYTES * 2);
            speaker.start();

            if (!MOCK_MIC) {
                try {
                    DataLine.Info mInfo = new DataLine.Info(TargetDataLine.class, fmt);
                    mic = (TargetDataLine) AudioSystem.getLine(mInfo);
                    mic.open(fmt, FRAME_BYTES * 2);
                    mic.start();
                } catch (Throwable ex) {
                    mic = null;
                }
            }

            sendThread = new Thread(this::loopSend, "lan-audio-send");
            recvThread = new Thread(this::loopRecv, "lan-audio-recv");
            sendThread.setDaemon(true); recvThread.setDaemon(true);
            sendThread.start(); recvThread.start();
        } catch (Exception e) {
            stop();
        }
    }

    private void loopSend() {
        try {
            OutputStream out = Objects.requireNonNull(socket).getOutputStream();
            byte[] buf = new byte[FRAME_BYTES];
            double phase = 0.0, step = 2 * Math.PI * TONE_HZ / SAMPLE_RATE;

            while (running && socket.isConnected()) {
                Integer ctl = pendingCtl;
                if (ctl != null) {
                    pendingCtl = null;
                    out.write((ctl >>> 24) & 0xFF);
                    out.write((ctl >>> 16) & 0xFF);
                    out.write((ctl >>> 8) & 0xFF);
                    out.write(ctl & 0xFF);
                    out.flush();
                }

                int n;
                if (!audioEnabled || MOCK_MIC || mic == null) {
                    for (int i = 0; i < FRAME_BYTES; i += 2) {
                        short s = (MOCK_MIC && audioEnabled) ? (short) (Math.sin(phase) * 12000) : 0;
                        buf[i] = (byte) (s & 0xFF);
                        buf[i + 1] = (byte) ((s >> 8) & 0xFF);
                        phase += step;
                        if (phase > 2 * Math.PI) phase -= 2 * Math.PI;
                    }
                    n = FRAME_BYTES;
                } else {
                    n = mic.read(buf, 0, FRAME_BYTES);
                    if (n <= 0) continue;
                }

                out.write((n >>> 24) & 0xFF);
                out.write((n >>> 16) & 0xFF);
                out.write((n >>> 8) & 0xFF);
                out.write(n & 0xFF);
                out.write(buf, 0, n);
                out.flush()
                ;
            }
        } catch (IOException e) {
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

                if (n == CTL_MUTE_ON) {
                    peerMuted = true;
                    if (speaker != null) speaker.flush();
                    continue;
                }
                if (n == CTL_MUTE_OFF) {
                    peerMuted = false;
                    if (speaker != null) speaker.flush();
                    continue;
                }

                if (n <= 0 || n > buf.length) break;
                if (!readFully(in, buf, 0, n)) break;
                if (!peerMuted && speaker != null) speaker.write(buf, 0, n);
            }
        } catch (IOException e) {
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
        return len == 0;
    }

    private void ensureNotRunning() throws IOException {
        if (running) throw new IOException("Audio already running");
    }

    public void setAudioEnabled(boolean enabled) {
        boolean prev = audioEnabled;
        audioEnabled = enabled;
        pendingCtl = enabled ? CTL_MUTE_OFF : CTL_MUTE_ON;
    }
}

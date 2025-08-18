package client;

import common.Protocol;

import javax.sound.sampled.*;
import java.util.function.BiConsumer;

/**
 * Ghi âm từ microphone và phát ra các frame PCM ~20ms.
 * - AudioFormat: 16kHz, 16-bit, mono, signed, little-endian
 * - Gọi onFrame.accept(buffer, length) mỗi 20ms
 */
public class VoiceRecorder {
    private TargetDataLine mic;
    private volatile boolean running = false;
    private Thread captureThread;

    /**
     * Bắt đầu ghi âm.
     * @param senderId  không bắt buộc (server của bạn sẽ override ID)
     * @param onFrame   callback nhận frame PCM (gọi ~mỗi 20ms)
     * @param onStart   callback khi đã mở line xong (có thể null)
     * @param onEnd     callback khi dừng/đóng line (có thể null)
     */
    public void start(String senderId,
                      BiConsumer<byte[], Integer> onFrame,
                      Runnable onStart,
                      Runnable onEnd) throws LineUnavailableException {
        if (running) return;

        AudioFormat fmt = new AudioFormat(
                Protocol.SAMPLE_RATE,
                Protocol.BYTES_PER_SAMPLE * 8,
                Protocol.CHANNELS,
                true,   // signed
                false   // little-endian
        );
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, fmt);
        mic = (TargetDataLine) AudioSystem.getLine(info);

        // buffer ~200ms để an toàn
        mic.open(fmt, Protocol.FRAME_BYTES * 10);
        mic.start();

        running = true;
        if (onStart != null) onStart.run();

        captureThread = new Thread(() -> {
            byte[] buf = new byte[Protocol.FRAME_BYTES]; // ~640 bytes @ 20ms
            try {
                while (running) {
                    int n = mic.read(buf, 0, buf.length); // blocking ~20ms
                    if (n > 0 && onFrame != null) {
                        onFrame.accept(buf, n);
                        // Nếu bên nhận giữ buffer lâu, hãy gửi bản copy:
                        // onFrame.accept(java.util.Arrays.copyOf(buf, n), n);
                    }
                }
            } finally {
                try {
                    mic.stop();
                    mic.close();
                } catch (Exception ignored) {}
                running = false;
                if (onEnd != null) onEnd.run();
            }
        }, "mic-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    /** Dừng ghi âm (thread sẽ tự thoát). */
    public void stop() {
        running = false;
    }
}

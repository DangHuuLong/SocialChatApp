package client;

import common.Protocol;

import javax.sound.sampled.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Phát các frame PCM ra loa theo đúng thứ tự nhận được.
 */
public class VoicePlayer {
    private SourceDataLine speaker;
    private volatile boolean running = false;
    private Thread playThread;

    // Hàng đợi khung âm thanh (khoảng vài giây buffer)
    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(400);

    /** Mở thiết bị phát và khởi chạy luồng phát. Gọi nhiều lần sẽ bỏ qua nếu đã chạy. */
    public void start() throws LineUnavailableException {
        if (running) return;

        AudioFormat fmt = new AudioFormat(
                Protocol.SAMPLE_RATE,
                Protocol.BYTES_PER_SAMPLE * 8,
                Protocol.CHANNELS,
                true,
                false
        );
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        speaker = (SourceDataLine) AudioSystem.getLine(info);

        // buffer ~400ms để mượt hơn khi mạng trễ
        speaker.open(fmt, Protocol.FRAME_BYTES * 20);
        speaker.start();

        running = true;

        playThread = new Thread(() -> {
            try {
                while (running) {
                    byte[] frame = queue.take(); // block tới khi có frame
                    if (!running) break;
                    if (frame != null && frame.length > 0) {
                        speaker.write(frame, 0, frame.length);
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                try {
                    speaker.drain();
                    speaker.stop();
                    speaker.close();
                } catch (Exception ignored) {}
                running = false;
            }
        }, "spk-play");
        playThread.setDaemon(true);
        playThread.start();
    }

    /** Thêm một frame PCM vào hàng đợi để phát. */
    public void enqueue(byte[] frame) {
        if (!running || frame == null || frame.length == 0) return;
        queue.offer(frame); // bỏ qua nếu đầy để tránh block
    }

    /** Dừng phát và đóng thiết bị loa. */
    public void stop() {
        if (!running) return;
        running = false;
        // "đánh thức" luồng phát nếu đang chờ
        queue.offer(new byte[0]);
    }
}
    
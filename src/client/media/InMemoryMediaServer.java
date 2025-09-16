package client.media;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

/**
 * InMemoryMediaServer
 * -------------------
 * - Streaming media từ bộ nhớ (RAM) qua HTTP nội bộ.
 * - JavaFX Media/MediaPlayer chỉ mở được URL (file:// hoặc http://), không mở trực tiếp byte[].
 * - Lớp này tạo 1 HttpServer 127.0.0.1:<port>, giữ map <id,Stream>, cho phép ghi chunk
 *   và phát lại theo dạng streaming (chunked transfer).
 *
 * Cách dùng:
 *   InMemoryMediaServer srv = InMemoryMediaServer.get();
 *   String id = "your-file-id";
 *   srv.open(id, "audio/mpeg"); // hoặc "video/mp4", "audio/wav", ...
 *   srv.write(id, chunkBytes, false); // nhiều lần
 *   srv.write(id, lastChunkBytes, true); // chunk cuối
 *   String url = srv.url(id); // "http://127.0.0.1:<port>/stream/your-file-id"
 *   Media media = new Media(url);
 *   MediaPlayer player = new MediaPlayer(media);
 *
 *   // Nếu cần hủy:
 *   srv.abort(id);
 */
public final class InMemoryMediaServer {

    /** Thông tin 1 stream: queue cố định các byte[] và trạng thái kết thúc */
    private static final class Stream {
        final String id;
        final String mime;
        final BlockingQueue<byte[]> chunks = new LinkedBlockingQueue<>();
        volatile boolean closed = false;
        Stream(String id, String mime) {
            this.id = id;
            this.mime = (mime == null || mime.isBlank()) ? "application/octet-stream" : mime;
        }
    }

    // Singleton
    private static volatile InMemoryMediaServer INSTANCE;

    public static InMemoryMediaServer get() {
        InMemoryMediaServer local = INSTANCE;
        if (local == null) {
            synchronized (InMemoryMediaServer.class) {
                local = INSTANCE;
                if (local == null) {
                    try {
                        local = new InMemoryMediaServer();
                    } catch (IOException e) {
                        throw new RuntimeException("Cannot start InMemoryMediaServer", e);
                    }
                    INSTANCE = local;
                }
            }
        }
        return local;
    }

    private final HttpServer server;
    private final int port;
    private final Map<String, Stream> streams = new ConcurrentHashMap<>();

    private InMemoryMediaServer() throws IOException {
        // cổng 0: hệ điều hành tự chọn cổng rảnh
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/stream", new Handler());
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mem-media-http");
            t.setDaemon(true);
            return t;
        }));
        server.start();
        System.out.println("[MEDIA] InMemoryMediaServer started at http://127.0.0.1:" + port);
    }

    /** Mở 1 stream mới cho id (nếu đã tồn tại sẽ ghi đè) */
    public void open(String id, String mime) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id is empty");
        streams.put(id, new Stream(id, mime));
    }

    /** Ghi 1 chunk dữ liệu vào stream; last=true là chunk cuối */
    public void write(String id, byte[] data, boolean last) {
        Stream s = streams.get(id);
        if (s == null) return; // im lặng (có thể đã bị abort)
        if (data != null && data.length > 0) {
            s.chunks.offer(data);
        } else {
            // để tránh block khi player đang chờ mà không có dữ liệu
            s.chunks.offer(new byte[0]);
        }
        if (last) s.closed = true;
    }

    /** Hủy stream, ngừng phát nếu đang mở */
    public void abort(String id) {
        Stream s = streams.remove(id);
        if (s != null) {
            s.closed = true;
            // đặt 1 marker trống để giải phóng consumer đang block
            s.chunks.offer(new byte[0]);
        }
    }

    /** Lấy URL phát cho id */
    public String url(String id) {
        return "http://127.0.0.1:" + port + "/stream/" + encode(id);
    }

    // ========== HTTP HANDLER ==========

    private final class Handler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            try {
                String path = ex.getRequestURI().getPath(); // /stream/<id>
                String[] parts = path.split("/", 3);
                if (parts.length != 3 || parts[2] == null || parts[2].isEmpty()) {
                    notFound(ex);
                    return;
                }
                String id = decode(parts[2]);
                Stream s = streams.get(id);
                if (s == null) {
                    notFound(ex);
                    return;
                }

                ex.getResponseHeaders().add("Content-Type", s.mime);
                // Bật streaming chunked; để -1 hoặc 0 đều ok cho chunked với HttpServer
                ex.sendResponseHeaders(200, 0);

                try (OutputStream os = ex.getResponseBody()) {
                    // phát cho đến khi closed và queue rỗng
                    while (true) {
                        byte[] chunk = s.chunks.poll(30, TimeUnit.SECONDS);
                        if (chunk == null) {
                            // timeout chờ chunk: nếu đã closed và không còn dữ liệu thì thoát
                            if (s.closed) break;
                            else continue;
                        }
                        if (chunk.length > 0) {
                            os.write(chunk);
                            os.flush();
                        }
                        // nếu closed và không còn dữ liệu đang chờ, thoát
                        if (s.closed && s.chunks.isEmpty()) break;
                    }
                } finally {
                    // Stream đã phát xong thì bỏ luôn
                    streams.remove(id);
                }
            } catch (Exception e) {
                try { ex.close(); } catch (Exception ignore) {}
            }
        }
    }

    // ========== Utils ==========

    private static void notFound(HttpExchange ex) throws IOException {
        byte[] msg = "404 Not Found".getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(404, msg.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(msg);
        } finally {
            ex.close();
        }
    }

    private static String encode(String s) {
        // rẻ tiền: thay khoảng trắng → %20, giữ ổn định cho id thường là UUID
        return s.replace(" ", "%20");
    }

    private static String decode(String s) {
        return s.replace("%20", " ");
    }
}

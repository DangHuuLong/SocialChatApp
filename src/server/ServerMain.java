package server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.*;

public class ServerMain {
    private static final int PORT = 5000;

    private final Set<ClientHandler> clients = ConcurrentHashMap.newKeySet();
    private final Map<String, ClientHandler> online = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public static void main(String[] args) {
        new ServerMain().start();
    }

    public void start() {
        try (ServerSocket ss = new ServerSocket(PORT)) {
            System.out.println("✅ Server started at port " + PORT);
            while (true) {
                Socket s = ss.accept();
                System.out.println("➡ Client connected: " + s.getRemoteSocketAddress());
                ClientHandler handler = new ClientHandler(s, clients, online);
                clients.add(handler);
                pool.submit(handler);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void shutdown() {
        try {
            for (ClientHandler h : clients) h.close();
        } catch (Exception ignored) {}
        pool.shutdownNow();
        System.out.println("🛑 Server shutdown.");
    }
}

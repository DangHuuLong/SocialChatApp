package server;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

public class ChatServer {
    private static final int PORT = 5000;

    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started. Listening on port: " + PORT);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("New client connected: " + clientSocket.getInetAddress().getHostAddress());

                ClientHandler clientHandler =
                        new ClientHandler(clientSocket, String.valueOf(System.currentTimeMillis()), this);
                clients.add(clientHandler);
                new Thread(clientHandler, "Client-" + clientHandler.getId()).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void broadcastMessage(String id, String message) {
        synchronized (clients) {
            for (ClientHandler c : clients) {
                if (!c.getId().equals(id)) {
                    c.sendMessage(id + " : " + message);
                }
            }
        }
    }
}

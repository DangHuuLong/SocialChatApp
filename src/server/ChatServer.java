package server;

import common.Message;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;

public class ChatServer {
    private static final int PORT = 5000;

    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started at port " + PORT);

            while (true) {
                Socket s = serverSocket.accept();
                String id = String.valueOf(System.currentTimeMillis());
                ClientHandler handler = new ClientHandler(s, id, this);
                clients.add(handler);
                new Thread(handler, "Client-" + id).start();
                System.out.println("Client connected: " + id);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void broadcast(Message msg, ClientHandler from) {
        synchronized (clients) {
                for (ClientHandler c : clients) {
                    if (msg.type.isVoice() && c == from) continue;
                    c.send(msg);
                }
        }
    }

    public void removeClient(Object h) {
        if (h instanceof ClientHandler ch) removeClient(ch);
    }
    public void removeClient(ClientHandler h) {
        clients.remove(h);
    }


    // tiện chạy nhanh
    public static void main(String[] args) {
        new ChatServer().startServer();
    }
}

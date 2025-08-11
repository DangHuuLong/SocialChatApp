package server;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {
    private final Socket mySocket;
    private final ChatServer chatServer;
    private final String id;
    private final BufferedReader in;      
    private final BufferedWriter out;     

    public ClientHandler(Socket mySocket, String id, ChatServer chatServer) {
        this.mySocket = mySocket;
        this.id = id;
        this.chatServer = chatServer;
        try {
            this.in  = new BufferedReader(new InputStreamReader(mySocket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new BufferedWriter(new OutputStreamWriter(mySocket.getOutputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                chatServer.broadcastMessage(this.id, line);
            }
        } catch (IOException ignored) {
        } finally {
            try { mySocket.close(); } catch (IOException ignored) {}
        }
    }

    public void sendMessage(String message) {
        try {
            out.write(message);
            out.write("\n");
            out.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getId() { return id; }
}

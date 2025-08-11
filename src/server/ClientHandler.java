package server;

import common.Message;
import common.MessageType;
import common.Protocol;

import java.io.*;
import java.net.Socket;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final ChatServer server;
    private final String id;

    private final DataInputStream  in;
    private final DataOutputStream out;

    public ClientHandler(Socket socket, String id, ChatServer server) {
        this.socket = socket;
        this.server = server;
        this.id = id;
        try {
            this.in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getId() { return id; }

    @Override
    public void run() {
        try {
            Protocol.writeText(out, "SYSTEM", "SYS_ID:" + this.id);
            out.flush();

            // Vòng đọc-broadcast
            while (true) {
                Message msg = Protocol.readMessage(in);

                // Ghi đè senderId từ server để client khác biết người gửi thực sự là ai
                msg.senderId = this.id;

                // Server sẽ broadcast CHO TẤT CẢ (kể cả 'from') — chỉnh ở ChatServer
                server.broadcast(msg, this);
            }
        } catch (IOException e) {
            System.out.println("Client " + id + " disconnected: " + e.getMessage());
        } finally {
            server.removeClient(this);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    public void send(Message m) {
        try {
            if (m.type == MessageType.TEXT) {
                Protocol.writeText(out, m.senderId, m.text);
            } else {
                Protocol.writeFile(out, m.senderId, m.filename, m.data);
            }
            out.flush();
        } catch (IOException e) {
            System.out.println("Send to " + id + " failed: " + e.getMessage());
        }
    }
}

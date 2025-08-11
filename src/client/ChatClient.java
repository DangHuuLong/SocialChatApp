package client;

import common.Protocol;

import java.io.*;
import java.net.Socket;

public class ChatClient {
    private final String host;
    private final int port;

    private Socket socket;
    private DataInputStream  in;
    private DataOutputStream out;

    public ChatClient() {
        this("127.0.0.1", 5000);
    }

    public ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect(MessageHandler handler) throws IOException {
        socket = new Socket(host, port);
        in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

        Thread t = new Thread(new ClientListener(in, handler), "ClientListener");
        t.setDaemon(true);
        t.start();
    }

    public void sendMessage(String text) throws IOException {
        Protocol.writeText(out, "", text); 
    }

    public void sendFile(java.io.File file) throws IOException {
        byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
        Protocol.writeFile(out, "", file.getName(), bytes);
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
    }
}

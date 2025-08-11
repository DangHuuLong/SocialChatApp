package client;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ChatClient {
    private final String host;
    private final int port;

    private Socket socket;
    private BufferedReader in;
    private BufferedWriter out;

    public ChatClient() {
        this("127.0.0.1", 5000);
    }

    public ChatClient(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public void connect(MessageHandler handler) throws IOException {
        socket = new Socket(host, port);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        Thread t = new Thread(new ClientListener(in, handler), "ClientListener");
        t.setDaemon(true);
        t.start();
    }

    public void sendMessage(String message) throws IOException {
        if (out == null) throw new IOException("Not connected");
        out.write(message);
        out.write("\n");
        out.flush();
    }

    public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (IOException ignored) {}
    }
}

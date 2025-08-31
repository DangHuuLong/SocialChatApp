package client;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

public class ClientConnection implements Closeable {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;

    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 2000);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void startListener(Consumer<String> onLine) {
        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) onLine.accept(line);
            } catch (IOException ignored) {}
        }, "server-listener");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void login(String username){ send("LOGIN " + username); }
    public void sendMessageAll(String text){ send("MSG " + text); }
    public void sendDirect(String to, String text){ send("DM " + to + " " + text); }
    public void who(){ send("WHO"); }
    public void quit(){ send("QUIT"); }

    private void send(String raw){ if (out != null) out.println(raw); }

    @Override public void close() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}

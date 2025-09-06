// client/ClientConnection.java
package client;

import java.io.*;
import java.net.*;
import java.util.function.Consumer;

import client.signaling.CallSignalingService;

public class ClientConnection {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private Thread readerThread;
    private CallSignalingService callService;

    private Consumer<String> onMessage;
    private Consumer<Exception> onError;

    public boolean connect(String host, int port) {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 5000);

            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    public void attachCallService(CallSignalingService s) {
        this.callService = s;
    }
    
    public synchronized void sendRaw(String line) {
        out.println(line);
        out.flush();
    }

    public void startListener(Consumer<String> onMessage, Consumer<Exception> onError) {
        this.onMessage = onMessage;
        this.onError   = onError;

        readerThread = new Thread(() -> {
            try {
                String line;
                while ((line = in.readLine()) != null) {
                	if (line.isEmpty()) continue;

                    if (callService != null && line.startsWith("CALL_")) {
                        if (callService.parseIncoming(line)) {
                            continue; 
                        }
                    }
                    if (onMessage != null) onMessage.accept(line);
                }
                if (onError != null) onError.accept(new EOFException("Server closed connection"));
            } catch (IOException e) {
                if (onError != null) onError.accept(e);
            }
        }, "server-listener");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void login(String username) {
        send("LOGIN " + username);
    }

    public void sendMessageAll(String text) {
        send("MSG " + text);
    }

    public void sendDirectMessage(String to, String text) {
        send("DM " + to + " " + text);
    }

    /** Gửi lệnh QUIT */
    public void quit() {
        send("QUIT");
    }

    public synchronized void send(String raw) {
        if (out != null) out.println(raw);
    }

    public boolean isAlive() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public void close() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }
    }
}

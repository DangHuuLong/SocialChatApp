package server;

import java.io.*;
import java.net.Socket;
import java.util.Set;
import java.util.Map;

public class ClientHandler implements Runnable {
    private final Socket socket;
    private final Set<ClientHandler> clients;
    private final Map<String, ClientHandler> online;

    private BufferedReader in;
    private PrintWriter out;
    private String username = "guest";

    public ClientHandler(Socket socket, Set<ClientHandler> clients, Map<String, ClientHandler> online) {
        this.socket = socket;
        this.clients = clients;
        this.online  = online;
    }

    @Override
    public void run() {
        try {
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            send("HELLO");

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("LOGIN ")) {
                    String u = line.substring(6).trim();
                    // TODO: xác thực DB tại đây (username/password)
                    if (u.isEmpty() || online.containsKey(u)) {
                        send("ERR LOGIN");
                    } else {
                        username = u;
                        online.put(username, this);
                        send("OK LOGIN " + username);
                        broadcast("🔵 " + username + " joined", true);
                    }

                } else if (line.startsWith("MSG ")) {
                    String msg = line.substring(4);
                    broadcast(username + ": " + msg, false);
                    send("OK SENT");

                } else if (line.startsWith("DM ")) {
                    // DM <user> <message>
                    int sp = line.indexOf(' ', 3);
                    if (sp > 3) {
                        String to = line.substring(3, sp).trim();
                        String msg = line.substring(sp + 1);
                        ClientHandler target = online.get(to);
                        if (target != null) {
                            target.send("[DM] " + username + ": " + msg);
                            send("OK DM");
                        } else {
                            send("ERR USER_OFFLINE");
                        }
                    } else send("ERR BAD_DM");

                } else if ("WHO".equalsIgnoreCase(line)) {
                    send("ONLINE " + String.join(",", online.keySet()));

                } else if ("QUIT".equalsIgnoreCase(line)) {
                    send("BYE");
                    break;

                } else {
                    send("ERR UNKNOWN_CMD");
                }
            }
        } catch (IOException ignored) {
        } finally {
            cleanup();
        }
    }

    public void send(String msg) {
        if (out != null) out.println(msg);
    }

    private void broadcast(String msg, boolean excludeSelf) {
        for (ClientHandler c : clients) {
            if (excludeSelf && c == this) continue;
            c.send("[ALL] " + msg);
        }
    }

    private void cleanup() {
        if (username != null) {
            online.remove(username, this);
            broadcast("🔴 " + username + " left", true);
        }
        clients.remove(this);
        close();
        System.out.println("⬅ Client disconnected: " + socket.getRemoteSocketAddress());
    }

    public void close() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }
}

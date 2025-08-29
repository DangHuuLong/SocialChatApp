package server;

import java.io.*;
import java.net.*;

public class ServerMain {
    private static final int PORT = 5000;

    public static void startServer() {
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started at port " + PORT);

            while (true) {
                Socket s = serverSocket.accept();
                String id = String.valueOf(System.currentTimeMillis());
                System.out.println("Client connected: " + id);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        startServer();
    }
}

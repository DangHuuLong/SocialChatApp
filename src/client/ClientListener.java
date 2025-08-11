package client;

import java.io.BufferedReader;

public class ClientListener implements Runnable {
    private final BufferedReader in;
    private final MessageHandler handler;

    public ClientListener(BufferedReader in, MessageHandler handler) {
        this.in = in;
        this.handler = handler;
    }

    @Override public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                if (handler != null) handler.onMessage(line); // đẩy lên UI
            }
        } catch (Exception e) {
            if (handler != null) handler.onMessage("[System] Disconnected: " + e.getMessage());
        }
    }
}

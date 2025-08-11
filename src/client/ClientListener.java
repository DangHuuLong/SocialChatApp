package client;

import common.Message;
import common.MessageType;
import common.Protocol;

import java.io.DataInputStream;
import java.io.IOException;

public class ClientListener implements Runnable {
    private final DataInputStream in;
    private final MessageHandler handler;

    public ClientListener(DataInputStream in, MessageHandler handler) {
        this.in = in;
        this.handler = handler;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Message m = Protocol.readMessage(in);
                if (m.type == MessageType.TEXT) {
                    handler.onText(m.senderId, m.text);
                } else {
                    handler.onFile(m.senderId, m.filename, m.data);
                }
            }
        } catch (IOException e) {
            handler.onText("System", "Disconnected: " + e.getMessage());
        }
    }
}

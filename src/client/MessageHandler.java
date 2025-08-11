package client;

public interface MessageHandler {
    void onText(String senderId, String text);
    void onFile(String senderId, String filename, byte[] data);
}

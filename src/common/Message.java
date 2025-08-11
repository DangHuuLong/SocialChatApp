package common;

public class Message {
    public MessageType type;
    public String senderId;

    // TEXT
    public String text;

    // FILE
    public String filename;
    public byte[] data;

    public static Message text(String senderId, String text) {
        Message m = new Message();
        m.type = MessageType.TEXT;
        m.senderId = senderId;
        m.text = text;
        return m;
    }

    public static Message file(String senderId, String filename, byte[] data) {
        Message m = new Message();
        m.type = MessageType.FILE;
        m.senderId = senderId;
        m.filename = filename;
        m.data = data;
        return m;
    }
}

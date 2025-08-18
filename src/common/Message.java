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
    
    public static Message voiceStart(String sender) {
        Message m = new Message();
        m.type = MessageType.VOICE_START;
        m.senderId = sender;
        return m;
    }
    public static Message voiceFrame(String sender, byte[] pcm) {
        Message m = new Message();
        m.type = MessageType.VOICE_FRAME;
        m.senderId = sender;
        m.data = pcm;     
        return m;
    }
    public static Message voiceEnd(String sender) {
        Message m = new Message();
        m.type = MessageType.VOICE_END;
        m.senderId = sender;
        return m;
    }

}

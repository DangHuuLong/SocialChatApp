package common;

import java.io.*;

public final class Protocol {
    private Protocol() {}

    // Ghi TEXT
    public static void writeText(DataOutputStream out, String senderId, String text) throws IOException {
        out.writeByte(MessageType.TEXT.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.writeUTF(text != null ? text : "");
        out.flush();
    }

    // Ghi FILE
    public static void writeFile(DataOutputStream out, String senderId, String filename, byte[] data) throws IOException {
        out.writeByte(MessageType.FILE.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.writeUTF(filename != null ? filename : "file.bin");
        out.writeInt(data != null ? data.length : 0);
        if (data != null && data.length > 0) out.write(data);
        out.flush();
    }

    // Đọc 1 message từ stream (block đến khi đủ)
    public static Message readMessage(DataInputStream in) throws IOException {
        byte typeCode = in.readByte();
        MessageType type = MessageType.from(typeCode);
        String sender = in.readUTF();

        if (type == MessageType.TEXT) {
            String text = in.readUTF();
            return Message.text(sender, text);
        } else {
            String filename = in.readUTF();
            int len = in.readInt();
            byte[] buf = new byte[len];
            in.readFully(buf);
            return Message.file(sender, filename, buf);
        }
    }
}

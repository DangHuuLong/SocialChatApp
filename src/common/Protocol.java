package common;

import java.io.*;

public final class Protocol {
    private Protocol() {}

    // ====== Cấu hình audio mặc định (PCM 16kHz, 16-bit, mono) ======
    public static final int SAMPLE_RATE = 16000;
    public static final int BYTES_PER_SAMPLE = 2;   // 16-bit
    public static final int CHANNELS = 1;
    public static final int FRAME_MS = 20;          // 20ms/khung
    public static final int FRAME_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS * FRAME_MS / 1000; // 640

    // ====== Ghi TEXT ======
    public static void writeText(DataOutputStream out, String senderId, String text) throws IOException {
        out.writeByte(MessageType.TEXT.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.writeUTF(text != null ? text : "");
        out.flush();
    }

    // ====== Ghi FILE ======
    public static void writeFile(DataOutputStream out, String senderId, String filename, byte[] data) throws IOException {
        out.writeByte(MessageType.FILE.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.writeUTF(filename != null ? filename : "file.bin");
        int len = (data != null) ? data.length : 0;
        out.writeInt(len);
        if (len > 0) out.write(data);
        out.flush();
    }

    // ====== Ghi VOICE (realtime) ======
    public static void writeVoiceStart(DataOutputStream out, String senderId) throws IOException {
        out.writeByte(MessageType.VOICE_START.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.flush();
    }

    public static void writeVoiceFrame(DataOutputStream out, String senderId, byte[] pcm, int len) throws IOException {
        out.writeByte(MessageType.VOICE_FRAME.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.writeInt(len);
        out.write(pcm, 0, len);
        out.flush();
    }

    public static void writeVoiceEnd(DataOutputStream out, String senderId) throws IOException {
        out.writeByte(MessageType.VOICE_END.code);
        out.writeUTF(senderId != null ? senderId : "");
        out.flush();
    }

    // ====== Đọc 1 message từ stream ======
    public static Message readMessage(DataInputStream in) throws IOException {
        byte typeCode = in.readByte();
        MessageType type = MessageType.from(typeCode);
        String sender = in.readUTF();

        switch (type) {
            case TEXT -> {
                String text = in.readUTF();
                return Message.text(sender, text);
            }
            case FILE -> {
                String filename = in.readUTF();
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                return Message.file(sender, filename, buf);
            }
            case VOICE_START -> {
                return Message.voiceStart(sender);
            }
            case VOICE_FRAME -> {
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                return Message.voiceFrame(sender, buf);
            }
            case VOICE_END -> {
                return Message.voiceEnd(sender);
            }
            default -> throw new IOException("Unsupported type: " + type);
        }
    }
}

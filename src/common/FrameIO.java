package common;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class FrameIO {
    private FrameIO(){}

    private static final int MAX_TOTAL_LEN = (1 << 22); // ~4MB

    public static void write(DataOutputStream out, Frame f) throws IOException {
        byte[] s = bytes(f.sender);
        byte[] r = bytes(f.recipient);
        byte[] b = bytes(f.body);
        byte[] id = bytes(f.transferId);

        int baseLen = 1 + 2 + 2 + 4 + s.length + r.length + b.length + 2 + id.length;
        boolean isChunk = (f.type == MessageType.FILE_CHUNK || f.type == MessageType.AUDIO_CHUNK);
        int extraLen = isChunk ? (4 + 1 + 4 + (f.bin == null ? 0 : f.bin.length)) : 0;

        int totalLen = baseLen + extraLen;
        if (totalLen < 0 || totalLen > MAX_TOTAL_LEN)
            throw new IOException("Invalid totalLen: " + totalLen);

        System.out.println("[DEBUG] Writing frame: type=" + f.type + ", totalLen=" + totalLen);
        out.writeInt(totalLen);
        out.writeByte(f.type.id);
        out.writeShort(s.length);
        out.writeShort(r.length);
        out.writeInt(b.length);
        out.writeShort(id.length);
        out.write(s);
        out.write(r);
        out.write(b);
        out.write(id);

        if (isChunk) {
            out.writeInt(f.seq);
            out.writeByte(f.last ? 1 : 0);
            out.writeInt(f.bin.length);
            out.write(f.bin);
        }
        out.flush();
    }

    public static Frame read(DataInputStream in) throws IOException {
        int totalLen;
        try {
            totalLen = in.readInt();
        } catch (EOFException e) {
            System.out.println("[DEBUG] EOF while reading frame length");
            return null;
        }
        if (totalLen < 0 || totalLen > MAX_TOTAL_LEN) {
            throw new IOException("Invalid totalLen: " + totalLen);
        }

        byte[] buf = in.readNBytes(totalLen);
        if (buf.length != totalLen) {
            System.out.println("[DEBUG] Truncated frame: expected=" + totalLen + ", read=" + buf.length);
            throw new EOFException("Truncated frame");
        }

        try (DataInputStream bin = new DataInputStream(new ByteArrayInputStream(buf))) {
            MessageType type = MessageType.from(bin.readByte());
            int sLen = bin.readUnsignedShort();
            int rLen = bin.readUnsignedShort();
            int bLen = bin.readInt();
            int idLen = bin.readUnsignedShort();

            String sender = readString(bin, sLen);
            String recipient = readString(bin, rLen);
            String body = readString(bin, bLen);
            String transferId = readString(bin, idLen);

            Frame f = new Frame(type, sender, recipient, body);
            f.transferId = transferId;

            if (type == MessageType.FILE_CHUNK || type == MessageType.AUDIO_CHUNK) {
                int seq = bin.readInt();
                boolean last = bin.readByte() == 1;
                int dLen = bin.readInt();
                byte[] data = (dLen == 0) ? new byte[0] : bin.readNBytes(dLen);
                if (data.length != dLen) {
                    System.out.println("[DEBUG] Truncated chunk data: expected=" + dLen + ", read=" + data.length);
                    throw new EOFException("Truncated chunk data");
                }

                f.seq = seq;
                f.last = last;
                f.bin = data;
            }
            System.out.println("[DEBUG] Read frame: type=" + type + ", transferId=" + transferId);
            return f;
        }
    }

    private static String readString(DataInputStream in, int len) throws IOException {
        if (len == 0) return "";
        byte[] a = in.readNBytes(len);
        if (a.length != len) {
            System.out.println("[DEBUG] Truncated string: expected=" + len + ", read=" + a.length);
            throw new EOFException("Truncated string");
        }
        return new String(a, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s) {
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}
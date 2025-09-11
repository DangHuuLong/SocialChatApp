package common;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class FrameIO {
    private FrameIO(){}

    private static final int MAX_TOTAL_LEN = (1 << 22); // ~4MB như cũ

    public static void write(DataOutputStream out, Frame f) throws IOException {
        byte[] s = bytes(f.sender);
        byte[] r = bytes(f.recipient);
        byte[] b = bytes(f.body);

        int baseLen = 1 + 2 + 2 + 4 + s.length + r.length + b.length;

        boolean isChunk = (f.type == MessageType.FILE_CHUNK || f.type == MessageType.AUDIO_CHUNK);
        int extraLen = 0;

        if (isChunk) {
            if (f.transferId == null) throw new IOException("Missing transferId");
            if (f.bin == null) f.bin = new byte[0];
            byte[] id = f.transferId.getBytes(StandardCharsets.UTF_8);
            extraLen = 2 + id.length + 4 + 1 + 4 + f.bin.length;
        }

        int totalLen = baseLen + extraLen;
        if (totalLen < 0 || totalLen > MAX_TOTAL_LEN)
            throw new IOException("Invalid totalLen: " + totalLen);

        // header + 3 strings
        out.writeInt(totalLen);
        out.writeByte(f.type.id);
        out.writeShort(s.length);
        out.writeShort(r.length);
        out.writeInt(b.length);
        out.write(s);
        out.write(r);
        out.write(b);

        // phần nhị phân thêm cho CHUNK
        if (isChunk) {
            byte[] id = f.transferId.getBytes(StandardCharsets.UTF_8);
            out.writeShort(id.length);
            out.write(id);
            out.writeInt(f.seq);
            out.writeByte(f.last ? 1 : 0);
            out.writeInt(f.bin.length);
            out.write(f.bin);
        }
        out.flush();
    }

    public static Frame read(DataInputStream in) throws IOException {
        int totalLen;
        try { totalLen = in.readInt(); }
        catch (EOFException e) { return null; }
        if (totalLen < 0 || totalLen > MAX_TOTAL_LEN)
            throw new IOException("Invalid totalLen: " + totalLen);

        byte[] buf = in.readNBytes(totalLen);
        if (buf.length != totalLen) throw new EOFException("Truncated frame");

        try (DataInputStream bin = new DataInputStream(new ByteArrayInputStream(buf))) {
            MessageType type = MessageType.from(bin.readByte());
            int sLen = bin.readUnsignedShort();
            int rLen = bin.readUnsignedShort();
            int bLen = bin.readInt();

            String sender    = readString(bin, sLen);
            String recipient = readString(bin, rLen);
            String body      = readString(bin, bLen);

            Frame f = new Frame(type, sender, recipient, body);

            if (type == MessageType.FILE_CHUNK || type == MessageType.AUDIO_CHUNK) {
                int idLen = bin.readUnsignedShort();
                String id = readString(bin, idLen);
                int seq   = bin.readInt();
                boolean last = bin.readByte() == 1;
                int dLen = bin.readInt();
                byte[] data = (dLen == 0) ? new byte[0] : bin.readNBytes(dLen);
                if (data.length != dLen) throw new EOFException("Truncated chunk data");

                f.transferId = id;
                f.seq = seq;
                f.last = last;
                f.bin = data;
            }
            return f;
        }
    }

    private static String readString(DataInputStream in, int len) throws IOException {
        if (len == 0) return "";
        byte[] a = in.readNBytes(len);
        if (a.length != len) throw new EOFException("Truncated string");
        return new String(a, StandardCharsets.UTF_8);
    }

    private static byte[] bytes(String s){
        return (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
    }
}

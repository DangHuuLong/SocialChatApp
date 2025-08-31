package common;

import java.io.*;
import java.nio.charset.StandardCharsets;

public final class FrameIO {
    private FrameIO(){}

    public static void write(DataOutputStream out, Frame f) throws IOException {
        byte[] s = bytes(f.sender);
        byte[] r = bytes(f.recipient);
        byte[] b = bytes(f.body);

        int totalLen = 1 + 2 + 2 + 4 + s.length + r.length + b.length; 
        out.writeInt(totalLen);
        out.writeByte(f.type.id);
        out.writeShort(s.length);
        out.writeShort(r.length);
        out.writeInt(b.length);
        out.write(s);
        out.write(r);
        out.write(b);
        out.flush();
    }

    public static Frame read(DataInputStream in) throws IOException {
        int totalLen;
        try { totalLen = in.readInt(); }
        catch (EOFException e) { return null; } 
        if (totalLen < 0 || totalLen > (1<<22)) 
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

            return new Frame(type, sender, recipient, body);
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

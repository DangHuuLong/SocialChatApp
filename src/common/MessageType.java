package common;

public enum MessageType {
    TEXT((byte)0),
    FILE((byte)1);

    public final byte code;
    MessageType(byte code) { this.code = code; }

    public static MessageType from(byte code) {
        return code == 0 ? TEXT : FILE;
    }
}

package common;

public enum MessageType {
    REGISTER((byte)1),
    DM((byte)2),
    ACK((byte)3),
    ERROR((byte)4),
    WHO((byte)5),
    ROOM_JOIN((byte)6),   
    ROOM_LEAVE((byte)7), 
    ROOM_MSG((byte)8);    

    public final byte id;
    MessageType(byte id){ this.id = id; }

    public static MessageType from(byte b){
        for (var t : values()) if (t.id == b) return t;
        throw new IllegalArgumentException("Unknown type: " + b);
    }
}

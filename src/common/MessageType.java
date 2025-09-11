package common;

public enum MessageType {
    REGISTER((byte)1),
    DM((byte)2),
    ACK((byte)3),
    ERROR((byte)4),
    WHO((byte)5),
    ROOM_JOIN((byte)6),
    ROOM_LEAVE((byte)7),
    ROOM_MSG((byte)8),

    FILE_META((byte)9),     // body = JSON: {from,to,name,mime,fileId,size}
    FILE_CHUNK((byte)10),   // nhị phân thêm ở đuôi: [idLen][id][seq][last][dataLen][data]
    AUDIO_META((byte)11),   // body = JSON: {from,to,codec,sampleRate,durationSec,audioId,size}
    AUDIO_CHUNK((byte)12);  // nhị phân thêm ở đuôi như FILE_CHUNK

    public final byte id;
    MessageType(byte id){ this.id = id; }

    public static MessageType from(byte b){
        for (var t : values()) if (t.id == b) return t;
        throw new IllegalArgumentException("Unknown type: " + b);
    }
}

package common;

import java.util.HashMap;
import java.util.Map;

public enum MessageType {
    TEXT        ((byte)0),
    FILE        ((byte)1),
    VOICE_START ((byte)2),
    VOICE_FRAME ((byte)3),
    VOICE_END   ((byte)4);

    public final byte code;
    MessageType(byte code) { this.code = code; }
    
    public boolean isVoice() {
        return this == VOICE_START || this == VOICE_FRAME || this == VOICE_END;
    }

    private static final Map<Byte, MessageType> LOOKUP = new HashMap<>();
    static {
        for (MessageType t : values()) LOOKUP.put(t.code, t);
    }

    public static MessageType from(byte code) {
        byte key = code; 
        MessageType t = LOOKUP.get(key);
        if (t == null) throw new IllegalArgumentException("Unknown MessageType code: " + (code & 0xFF));
        return t;
    }
}

// common/MessageType.java
package common;

/**
 * THỐNG NHẤT ID GIỮA CLIENT & SERVER
 * -----------------------------------
 * - Mỗi type có 1 byte id duy nhất để FrameIO.write/read serialize.
 * - Client & Server PHẢI dùng chung file này để tránh lệch ID (gây decode sai / timeout).
 *
 * Quy ước dải ID:
 *   0  -  9  : Core control / messaging
 *  10  - 19  : Binary transfer (file/audio) - request/stream
 *  20  - 29  : Push/event + Call signaling
 *  30  - 39  : (Reserved) mở rộng trong tương lai
 */
public enum MessageType {
    // ===== Core =====
    LOGIN       ((byte) 0),   // sender=username
    REGISTER    ((byte) 1),   // sender=username
    DM          ((byte) 2),   // sender, recipient, body=text
    ACK         ((byte) 3),   // body=text; có thể kèm transferId để ACK upload
    ERROR       ((byte) 4),   // body=error text

    // ===== History =====
    HISTORY     ((byte) 5),   // body=payload hiển thị (ví dụ "[HIST IN] a: hi")

    // ===== Binary transfer: File / Audio (request + stream) =====
    FILE_META   ((byte) 9),   // body=JSON {"from","to","name","mime","fileId","size"}
    FILE_CHUNK  ((byte)10),   // nhị phân: transferId, seq, last, data

    AUDIO_META  ((byte)11),   // body=JSON {"from","to","codec","sampleRate","durationSec","audioId","size"}
    AUDIO_CHUNK ((byte)12),   // nhị phân: transferId, seq, last, data

    // ===== Event to recipient (server -> peer) =====
    FILE_EVT    ((byte)13),   // body=JSON {"from","to","id","name","mime","bytes"}
    AUDIO_EVT   ((byte)14),   // body=JSON {"from","to","id","name","mime","bytes","duration"}

    // ===== Call signaling =====
    CALL_INVITE ((byte)20),   // sender=caller, recipient=callee
    CALL_ACCEPT ((byte)21),
    CALL_REJECT ((byte)22),
    CALL_CANCEL ((byte)23),
    CALL_BUSY   ((byte)24),
    CALL_END    ((byte)25),
    CALL_OFFER  ((byte)26),   // body=SDP/offer JSON (hoặc b64 tuỳ bạn)
    CALL_ANSWER ((byte)27),   // body=answer JSON
    CALL_ICE    ((byte)28),   // body=candidate JSON/b64
    CALL_OFFLINE((byte)29),   // notify caller khi callee offline
    DOWNLOAD_FILE((byte)30); 
    public final byte id;
    MessageType(byte id){ this.id = id; }

    public static MessageType from(byte b){
        for (var t : values()) if (t.id == b) return t;
        throw new IllegalArgumentException("Unknown MessageType id: " + b);
    }
}

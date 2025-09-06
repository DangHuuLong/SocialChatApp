package client.signaling;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import client.ClientConnection; // đổi package theo dự án bạn

/**
 * Màng mỏng gửi/nhận CALL_* qua textline.
 * - Gửi: format dòng và đẩy vào ClientConnection.sendRaw(...)
 * - Nhận: parse từng dòng CALL_* và gọi listener
 */
public class CallSignalingService {

    private final ClientConnection conn;
    private CallSignalListener listener;

    public CallSignalingService(ClientConnection connection) {
        this.conn = Objects.requireNonNull(connection);
        // cho ClientConnection biết service này để chặn CALL_* sớm
        this.conn.attachCallService(this);
    }

    public void setListener(CallSignalListener l) { this.listener = l; }

    // ========= Sender APIs =========
    public void sendInvite(String toUser, String callId) { conn.sendRaw("CALL_INVITE " + toUser + " " + callId); }
    public void sendAccept(String toUser, String callId) { conn.sendRaw("CALL_ACCEPT " + toUser + " " + callId); }
    public void sendReject(String toUser, String callId) { conn.sendRaw("CALL_REJECT " + toUser + " " + callId); }
    public void sendCancel(String toUser, String callId) { conn.sendRaw("CALL_CANCEL " + toUser + " " + callId); }
    public void sendBusy  (String toUser, String callId) { conn.sendRaw("CALL_BUSY "   + toUser + " " + callId); }
    public void sendEnd   (String toUser, String callId) { conn.sendRaw("CALL_END "    + toUser + " " + callId); }

    public void sendOffer (String toUser, String callId, String sdpUtf8) {
        conn.sendRaw("CALL_OFFER " + toUser + " " + callId + " " + b64(sdpUtf8));
    }
    public void sendAnswer(String toUser, String callId, String sdpUtf8) {
        conn.sendRaw("CALL_ANSWER " + toUser + " " + callId + " " + b64(sdpUtf8));
    }
    public void sendIce   (String toUser, String callId, String candidateUtf8) {
        conn.sendRaw("CALL_ICE " + toUser + " " + callId + " " + b64(candidateUtf8));
    }

    // ========= Receiver (from ClientConnection) =========
    /** @return true nếu đã xử lý (là CALL_*), false để ClientConnection xử lý tiếp các lệnh khác */
    public boolean parseIncoming(String line) {
        if (line == null || !line.startsWith("CALL_")) return false;
        if (listener == null) return true; // nuốt luôn để không rơi xuống lớp khác

        // Server chuẩn hoá: CALL_XXX <fromUser> <callId> [payload]
        String[] p = line.split(" ", 4);
        String cmd = p[0];
        if (cmd.equals("CALL_OFFER") || cmd.equals("CALL_ANSWER") || cmd.equals("CALL_ICE")) {
            if (p.length < 4) return true; // bad line, bỏ qua
        } else {
            if (p.length < 3) return true;
        }

        String fromUser = p[1];
        String callId   = p[2];

        switch (cmd) {
            case "CALL_INVITE" -> listener.onInvite(fromUser, callId);
            case "CALL_ACCEPT" -> listener.onAccept(fromUser, callId);
            case "CALL_REJECT" -> listener.onReject(fromUser, callId);
            case "CALL_CANCEL" -> listener.onCancel(fromUser, callId);
            case "CALL_BUSY"   -> listener.onBusy(fromUser, callId);
            case "CALL_END"    -> listener.onEnd(fromUser, callId);

            case "CALL_OFFER"  -> listener.onOffer(fromUser, callId, unb64(p[3]));
            case "CALL_ANSWER" -> listener.onAnswer(fromUser, callId, unb64(p[3]));
            case "CALL_ICE"    -> listener.onIce(fromUser, callId, unb64(p[3]));

            case "CALL_OFFLINE" -> listener.onOffline(fromUser /* actually 'toUser' on server side */, callId);
        }
        return true;
    }

    // ========= Helpers =========
    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String unb64(String b) {
        return new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8);
    }
}

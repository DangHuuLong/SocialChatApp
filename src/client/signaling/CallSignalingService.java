package client.signaling;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

import client.ClientConnection;
import common.Frame;
import common.MessageType;

public class CallSignalingService {
    private final ClientConnection conn;
    private CallSignalListener listener;
    private String self; 

    public CallSignalingService(ClientConnection connection) {
        this.conn = Objects.requireNonNull(connection);
        this.conn.attachCallService(this);
    }

    public void setSelfUser(String username){ this.self = username; }

    public void setListener(CallSignalListener l) { this.listener = l; }

    public void sendInvite(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_INVITE, self, toUser, callId));
    }
    public void sendAccept(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_ACCEPT, self, toUser, callId));
    }
    public void sendReject(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_REJECT, self, toUser, callId));
    }
    public void sendCancel(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_CANCEL, self, toUser, callId));
    }
    public void sendBusy(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_BUSY, self, toUser, callId));
    }
    public void sendEnd(String toUser, String callId){
        send(Frame.callNoPayload(MessageType.CALL_END, self, toUser, callId));
    }

    public void sendOffer(String toUser, String callId, String sdpUtf8){
        send(Frame.callWithPayload(MessageType.CALL_OFFER, self, toUser, callId, b64(sdpUtf8)));
    }
    public void sendAnswer(String toUser, String callId, String sdpUtf8){
        send(Frame.callWithPayload(MessageType.CALL_ANSWER, self, toUser, callId, b64(sdpUtf8)));
    }
    public void sendIce(String toUser, String callId, String candidateUtf8){
        send(Frame.callWithPayload(MessageType.CALL_ICE, self, toUser, callId, b64(candidateUtf8)));
    }

    private void send(Frame f){
        try { conn.sendFrame(f); } catch (Exception e) { e.printStackTrace(); }
    }

    public boolean tryHandleIncoming(common.Frame f) {
        if (f == null) return false;
        MessageType t = f.type;
        if (t != MessageType.CALL_INVITE &&
            t != MessageType.CALL_ACCEPT &&
            t != MessageType.CALL_REJECT &&
            t != MessageType.CALL_CANCEL &&
            t != MessageType.CALL_BUSY   &&
            t != MessageType.CALL_END    &&
            t != MessageType.CALL_OFFER  &&
            t != MessageType.CALL_ANSWER &&
            t != MessageType.CALL_ICE) {
            return false;
        }
        if (listener == null) return true; 

        String fromUser = f.sender;
        String callId   = jsonGet(f.body, "callId");
        switch (t){
            case CALL_INVITE -> listener.onInvite(fromUser, callId);
            case CALL_ACCEPT -> listener.onAccept(fromUser, callId);
            case CALL_REJECT -> listener.onReject(fromUser, callId);
            case CALL_CANCEL -> listener.onCancel(fromUser, callId);
            case CALL_BUSY   -> listener.onBusy(fromUser, callId);
            case CALL_END    -> listener.onEnd(fromUser, callId);
            case CALL_OFFER  -> listener.onOffer(fromUser, callId, unb64(jsonGet(f.body,"payload")));
            case CALL_ANSWER -> listener.onAnswer(fromUser, callId, unb64(jsonGet(f.body,"payload")));
            case CALL_ICE    -> listener.onIce(fromUser, callId, unb64(jsonGet(f.body,"payload")));
            default -> {}
        }
        return true;
    }

    private static String b64(String s){
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String unb64(String b64){
        if (b64 == null) return "";
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }
}

package client.signaling;

public interface CallSignalListener {
    // Thiết lập/chung
    void onInvite(String fromUser, String callId);
    void onAccept(String fromUser, String callId);
    void onReject(String fromUser, String callId);
    void onCancel(String fromUser, String callId);
    void onBusy(String fromUser, String callId);
    void onEnd(String fromUser, String callId);
    void onOffline(String toUser, String callId);

    // SDP/ICE
    void onOffer(String fromUser, String callId, String sdp);
    void onAnswer(String fromUser, String callId, String sdp);
    void onIce(String fromUser, String callId, String candidate);
}

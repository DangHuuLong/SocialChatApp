package server.signaling;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;
import server.ClientHandler;

/**
 * Online-only call routing for LAN.
 * Singleton to avoid touching ServerMain; ClientHandler gets it via getInstance().
 */
public final class CallRouter {

    private static final CallRouter INSTANCE = new CallRouter();
    public static CallRouter getInstance() { return INSTANCE; }

    private final ConcurrentHashMap<String, ClientHandler> online = new ConcurrentHashMap<>();

    private CallRouter() {}

    // ---- Presence management ----
    public void register(String username, ClientHandler handler) {
        if (username == null || username.isBlank() || handler == null) return;
        online.put(username, handler);
    }

    public void unregister(String username, ClientHandler handler) {
        if (username == null || username.isBlank()) return;
        // Only remove if same handler
        online.computeIfPresent(username, (u, h) -> (h == handler) ? null : h);
    }

    public boolean isOnline(String username) {
        return online.containsKey(username);
    }

    // ---- Utilities ----
    private boolean sendTo(String username, String line) {
        ClientHandler h = online.get(username);
        if (h == null) return false;
        h.sendLine(line);
        return true;
    }

    private void sendOfflineToCaller(String caller, String callee, String callId) {
        sendTo(caller, "CALL_OFFLINE " + callee + " " + callId);
    }

    // ---- Forwarders (server normalizes 'from' for the receiver side) ----

    public void invite(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { sendOfflineToCaller(fromUser, toUser, callId); return; }
        sendTo(toUser, "CALL_INVITE " + fromUser + " " + callId);
    }

    public void accept(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { sendOfflineToCaller(fromUser, toUser, callId); return; }
        sendTo(toUser, "CALL_ACCEPT " + fromUser + " " + callId);
    }

    public void reject(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { return; }
        sendTo(toUser, "CALL_REJECT " + fromUser + " " + callId);
    }

    public void cancel(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { return; }
        sendTo(toUser, "CALL_CANCEL " + fromUser + " " + callId);
    }

    public void busy(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { return; }
        sendTo(toUser, "CALL_BUSY " + fromUser + " " + callId);
    }

    public void end(String fromUser, String toUser, String callId) {
        if (!isOnline(toUser)) { return; }
        sendTo(toUser, "CALL_END " + fromUser + " " + callId);
    }

    public void offer(String fromUser, String toUser, String callId, String b64Sdp) {
        if (!isOnline(toUser)) { sendOfflineToCaller(fromUser, toUser, callId); return; }
        sendTo(toUser, "CALL_OFFER " + fromUser + " " + callId + " " + b64Sdp);
    }

    public void answer(String fromUser, String toUser, String callId, String b64Sdp) {
        if (!isOnline(toUser)) { sendOfflineToCaller(fromUser, toUser, callId); return; }
        sendTo(toUser, "CALL_ANSWER " + fromUser + " " + callId + " " + b64Sdp);
    }

    public void ice(String fromUser, String toUser, String callId, String b64Cand) {
        if (!isOnline(toUser)) { return; }
        sendTo(toUser, "CALL_ICE " + fromUser + " " + callId + " " + b64Cand);
    }
}

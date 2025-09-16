package server.signaling;

import server.ClientHandler;
import common.Frame;
import common.MessageType;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Online-only call routing (Frame-based).
 * Singleton. ClientHandler gọi CallRouter.getInstance().
 */
public final class CallRouter {

    private static final CallRouter INSTANCE = new CallRouter();
    public static CallRouter getInstance() { return INSTANCE; }

    private final ConcurrentHashMap<String, ClientHandler> online = new ConcurrentHashMap<>();

    private CallRouter() {}

    /* ============ Presence management ============ */
    public void register(String username, ClientHandler handler) {
        if (username == null || username.isBlank() || handler == null) return;
        online.put(username, handler);
    }

    public void unregister(String username, ClientHandler handler) {
        if (username == null || username.isBlank()) return;
        online.computeIfPresent(username, (u, h) -> (h == handler) ? null : h);
    }

    public boolean isOnline(String username) {
        return online.containsKey(username);
    }

    /* ============ Frame routing API (mới) ============ */

    /**
     * Route 1 Frame CALL_* từ {@code fromUser} sang người nhận (f.recipient).
     * Convention:
     * - CALL_INVITE/ACCEPT/REJECT/CANCEL/BUSY/END/CALL_OFFLINE: body = callId
     * - CALL_OFFER/ANSWER/ICE: body giữ nguyên payload (nên chứa callId bên trong payload, tuỳ bạn định nghĩa)
     */
    public void route(String fromUser, Frame f) {
        if (f == null || fromUser == null || fromUser.isBlank()) return;

        final String toUser = safe(f.recipient);
        switch (f.type) {
            case CALL_INVITE, CALL_ACCEPT, CALL_REJECT, CALL_CANCEL, CALL_BUSY, CALL_END -> {
                // body = callId
                if (!forwardOrOffline(fromUser, toUser, cloneForForward(fromUser, toUser, f.type, f.body))) {
                    sendOffline(fromUser, toUser, f.body /* callId */);
                }
            }

            case CALL_OFFER, CALL_ANSWER, CALL_ICE -> {
                // body = payload (thường là JSON/b64) – chuyển tiếp nguyên trạng
                if (!forwardOrOffline(fromUser, toUser, cloneForForward(fromUser, toUser, f.type, f.body))) {
                    // cần callId để thông báo OFFLINE; cố gắng rút callId từ JSON payload nếu có
                    String callId = tryExtractCallId(f.body);
                    sendOffline(fromUser, toUser, callId);
                }
            }

            default -> {
                // Không phải CALL_*: bỏ qua
            }
        }
    }

    /* ============ Helpers ============ */

    /** Gửi frame đến user đích; nếu offline trả về false. */
    private boolean forwardOrOffline(String fromUser, String toUser, Frame toSend) {
        ClientHandler h = online.get(toUser);
        if (h == null) return false;
        // khẳng định lại sender/recipient cho chuẩn
        toSend.sender    = fromUser;
        toSend.recipient = toUser;
        try {
            h.sendFrame(toSend);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Gửi CALL_OFFLINE về caller khi callee không online. body = callId (nếu biết). */
    private void sendOffline(String caller, String callee, String callIdOrNull) {
        ClientHandler c = online.get(caller);
        if (c == null) return;
        Frame off = new Frame(MessageType.CALL_OFFLINE, callee /*sender?*/ , caller, safe(callIdOrNull));
        // Convention: sender = callee để client hiển thị "callee offline".
        off.sender    = callee;
        off.recipient = caller;
        try { c.sendFrame(off); } catch (Exception ignored) {}
    }

    /** Tạo bản sao mới để forward (tránh dùng chung instance do có thể chỉnh sender/recipient). */
    private Frame cloneForForward(String from, String to, MessageType type, String body) {
        return new Frame(type, from, to, body == null ? "" : body);
    }

    private static String safe(String s) { return (s == null ? "" : s); }

    /** Thử rút callId từ chuỗi body dạng JSON đơn giản {"callId":"..."} */
    private static String tryExtractCallId(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"callId\"");
        if (i < 0) return "";
        int colon = body.indexOf(':', i + 8);
        if (colon < 0) return "";
        int j = colon + 1;
        while (j < body.length() && Character.isWhitespace(body.charAt(j))) j++;
        if (j >= body.length()) return "";
        if (body.charAt(j) == '"') {
            int end = body.indexOf('"', j + 1);
            if (end > j) return body.substring(j + 1, end);
        } else {
            int end = j;
            while (end < body.length() && "-_@.a-zA-Z0-9".indexOf(body.charAt(end)) >= 0) end++;
            return body.substring(j, end);
        }
        return "";
    }
}

package client.media;

import java.io.IOException;

public class CallOffer {
    public final String host;
    public final int vport; // video port
    public final int aport; // audio port

    public CallOffer(String host, int vport, int aport) {
        this.host = host; this.vport = vport; this.aport = aport;
    }

    public String toJson() {
        return "{\"host\":\"" + host + "\",\"vport\":" + vport + ",\"aport\":" + aport + "}";
    }

    public static CallOffer fromJson(String json) throws IOException {
        try {
            String s = json.trim();
            String host = pickString(s, "\"host\"");
            int vport   = pickInt(s, "\"vport\"");
            int aport   = pickInt(s, "\"aport\"");
            if (host == null || vport <= 0 || aport <= 0) throw new IOException("Bad OFFER JSON");
            return new CallOffer(host, vport, aport);
        } catch (Exception e) {
            throw new IOException("Parse OFFER failed", e);
        }
    }

    private static String pickString(String s, String key) {
        int i = s.indexOf(key); if (i < 0) return null;
        int c = s.indexOf(':', i), q1 = s.indexOf('"', c + 1), q2 = s.indexOf('"', q1 + 1);
        return (q1 > 0 && q2 > q1) ? s.substring(q1 + 1, q2) : null;
    }
    private static int pickInt(String s, String key) {
        int i = s.indexOf(key); if (i < 0) return -1;
        int c = s.indexOf(':', i), e = c + 1;
        while (e < s.length() && Character.isWhitespace(s.charAt(e))) e++;
        int e2 = e; while (e2 < s.length() && Character.isDigit(s.charAt(e2))) e2++;
        return Integer.parseInt(s.substring(e, e2));
    }
}

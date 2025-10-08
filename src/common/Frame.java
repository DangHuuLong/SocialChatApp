package common;

public class Frame {
    public MessageType type;
    public String sender;
    public String recipient;
    public String body;

    public String transferId;
    public int seq;
    public boolean last;
    public byte[] bin;

    public static final int CHUNK_SIZE = 64 * 1024;
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    public static final int MAX_AUDIO_SECONDS = 30;

    public Frame(MessageType type, String sender, String recipient, String body) {
        this.type = type;
        this.sender = sender;
        this.recipient = recipient;
        this.body = body;
    }

    public static Frame register(String name) { return new Frame(MessageType.REGISTER, name, "", ""); }
    public static Frame dm(String from, String to, String text) { return new Frame(MessageType.DM, from, to, text); }
    public static Frame ack(String text) { return new Frame(MessageType.ACK, "", "", text); }
    public static Frame error(String text) { return new Frame(MessageType.ERROR, "", "", text); }

    public static Frame fileMeta(String from, String to, String name, String mime, String fileId, long size) {
        String j = "{\"from\":\"" + esc(from) + "\",\"to\":\"" + esc(to) + "\",\"name\":\"" + esc(name) + "\"," +
                   "\"mime\":\"" + esc(mime) + "\",\"fileId\":\"" + esc(fileId) + "\",\"size\":" + size + "}";
        return new Frame(MessageType.FILE_META, from, to, j);
    }

    public static Frame audioMeta(String from, String to, String codec, int sampleRate, int durationSec,
                                  String audioId, long size) {
        String j = "{\"from\":\"" + esc(from) + "\",\"to\":\"" + esc(to) + "\",\"codec\":\"" + esc(codec) + "\"," +
                   "\"sampleRate\":" + sampleRate + ",\"durationSec\":" + durationSec + "," +
                   "\"audioId\":\"" + esc(audioId) + "\",\"size\":" + size + "}";
        return new Frame(MessageType.AUDIO_META, from, to, j);
    }

    public static Frame fileChunk(String from, String to, String fileId, int seq, boolean last, byte[] data) {
        Frame f = new Frame(MessageType.FILE_CHUNK, from, to, "");
        f.transferId = fileId;
        f.seq = seq;
        f.last = last;
        f.bin = data;
        return f;
    }

    public static Frame audioChunk(String from, String to, String audioId, int seq, boolean last, byte[] data) {
        Frame f = new Frame(MessageType.AUDIO_CHUNK, from, to, "");
        f.transferId = audioId;
        f.seq = seq;
        f.last = last;
        f.bin = data;
        return f;
    }

    public static Frame callNoPayload(MessageType t, String from, String to, String callId) {
        String j = "{\"callId\":\"" + esc(callId) + "\"}";
        return new Frame(t, from, to, j);
    }

    public static Frame callWithPayload(MessageType t, String from, String to, String callId, String payloadB64) {
        String j = "{\"callId\":\"" + esc(callId) + "\",\"payload\":\"" + esc(payloadB64) + "\"}";
        return new Frame(t, from, to, j);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
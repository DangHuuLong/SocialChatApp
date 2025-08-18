package client;

import common.Message;
import common.MessageType;
import common.Protocol;

import java.io.DataInputStream;
import java.io.IOException;

public class ClientListener implements Runnable {
    private final DataInputStream in;
    private final MessageHandler handler;
    private final VoiceHandler voiceHandler; // có thể null
    private String myId;                     // nhận từ SYSTEM SYS_ID:<id>

    public ClientListener(DataInputStream in, MessageHandler handler) {
        this(in, handler, null);
    }

    public ClientListener(DataInputStream in, MessageHandler handler, VoiceHandler voiceHandler) {
        this.in = in;
        this.handler = handler;
        this.voiceHandler = voiceHandler;
    }

    @Override
    public void run() {
        try {
            while (true) {
                Message m = Protocol.readMessage(in);

                // Bắt myId từ system text: "SYS_ID:<id>"
                if (m.type == MessageType.TEXT && "SYSTEM".equals(m.senderId) && m.text != null) {
                    if (m.text.startsWith("SYS_ID:")) {
                        myId = m.text.substring("SYS_ID:".length());
                        // tuỳ chọn: không đẩy ra UI; nếu muốn hiện thì bỏ continue.
                        continue;
                    }
                }

                switch (m.type) {
                    case TEXT -> {
                        handler.onText(m.senderId, m.text);
                    }
                    case FILE -> {
                        String fn = (m.filename != null ? m.filename : "file.bin");
                        byte[] data = (m.data != null ? m.data : new byte[0]);
                        handler.onFile(m.senderId, fn, data);
                    }
                    case VOICE_START -> {
                        if (voiceHandler == null) break;
                        if (m.senderId != null && m.senderId.equals(myId)) break; // bỏ voice của chính mình
                        voiceHandler.onVoiceStart(m.senderId);
                    }
                    case VOICE_FRAME -> {
                        if (voiceHandler == null) break;
                        if (m.senderId != null && m.senderId.equals(myId)) break;
                        if (m.data != null && m.data.length > 0) {
                            voiceHandler.onVoiceFrame(m.senderId, m.data);
                        }
                    }
                    case VOICE_END -> {
                        if (voiceHandler == null) break;
                        if (m.senderId != null && m.senderId.equals(myId)) break;
                        voiceHandler.onVoiceEnd(m.senderId);
                    }
                    default -> {
                        // Không hỗ trợ loại khác
                    }
                }
            }
        } catch (IOException e) {
            handler.onText("SYSTEM", "Disconnected: " + e.getMessage());
        }
    }
}

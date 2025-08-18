package client;

public interface VoiceHandler {
    void onVoiceStart(String senderId);
    void onVoiceFrame(String senderId, byte[] pcm);
    void onVoiceEnd(String senderId);
}

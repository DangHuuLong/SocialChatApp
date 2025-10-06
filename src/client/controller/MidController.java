package client.controller;

import client.ClientConnection;
import client.controller.mid.CallHandler;
import client.controller.mid.FileHandler;
import client.controller.mid.MediaHandler;
import client.controller.mid.MessageHandler;
import client.controller.mid.UIMessageHandler;
import client.controller.mid.UtilHandler;
import client.controller.mid.VoiceRecordHandler;
import client.media.LanAudioSession;
import client.media.LanVideoSession;
import client.signaling.CallSignalListener;
import client.signaling.CallSignalingService;
import common.Frame;
import common.User;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import server.dao.UserDAO;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedOutputStream;
import java.io.File;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MidController implements CallSignalListener {
	public static final class MsgView {
	    private final long epochMillis;
	    private final boolean incoming;
	    private final String text;
	    public MsgView(long epochMillis, boolean incoming, String text) {
	        this.epochMillis = epochMillis; this.incoming = incoming; this.text = text;
	    }
	    public long epochMillis(){ return epochMillis; }
	    public boolean incoming(){ return incoming; }
	    public String text(){ return text; }
	}
	public List<MsgView> exportMessagesForSearch() {
	    return new ArrayList<>(this.messageSnapshot);
	}
    private Label currentChatName;
    private Label currentChatStatus;
    private VBox messageContainer;
    private TextField messageField;
    private Button logoutBtn;

    private RightController rightController;
    private CallSignalingService callSvc;
    private Stage callStage;
    private VideoCallController callCtrl;
    private String currentCallId;
    private String currentPeer;
    private boolean isCaller = false;

    private User currentUser;
    private User selectedUser;
    private ClientConnection connection;
    private User currentPeerUser;

    private LanVideoSession videoSession;
    private LanAudioSession audioSession;

    private ChangeListener<Number> autoScrollListener;
    private final List<MsgView> messageSnapshot = new ArrayList<>();

    private final Map<String, List<Frame>> pendingFileEvents = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToName = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToMime = new ConcurrentHashMap<>();
    public final Map<String, HBox> outgoingFileBubbles = new ConcurrentHashMap<>();

    private final Map<String, BufferedOutputStream> dlOut = new ConcurrentHashMap<>();
    private final Map<String, File> dlPath = new ConcurrentHashMap<>();

    private final VoiceRecordHandler voiceRecordHandler = new VoiceRecordHandler();
    
    private final ArrayDeque<HBox> pendingOutgoingTexts = new ArrayDeque<>();

    public void bind(Label currentChatName, Label currentChatStatus, VBox messageContainer, TextField messageField) {
        this.currentChatName = currentChatName;
        this.currentChatStatus = currentChatStatus;
        this.messageContainer = messageContainer;
        this.messageField = messageField;

        if (this.messageField != null) this.messageField.setOnAction(e -> onSendMessage());
    }

    public void setRightController(RightController rc) { this.rightController = rc; }
    public void setCurrentUser(User user) { this.currentUser = user; }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (this.connection != null) {
            this.connection.setMidController(this);
            this.connection.startListener(
                f -> Platform.runLater(() -> handleServerFrame(f)),
                err -> System.err.println("[NET] Disconnected: " + err)
            );
        }
    }

    public void openConversation(User u) {
        System.out.println("[OPEN] conversation with " + u.getUsername());
        this.selectedUser = u;
        if (currentChatName != null) currentChatName.setText(u.getUsername());

        try {
            UserDAO.Presence p = UserDAO.getPresence(u.getId());
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;
            applyStatusLabel(currentChatStatus, online, lastSeen);
            if (rightController != null) rightController.showUser(u, online, lastSeen);
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            if (rightController != null) rightController.showUser(u, false, null);
        }

        if (messageContainer != null) {
            if (messageContainer.getChildren().size() > 100) {
                messageContainer.getChildren().remove(0, messageContainer.getChildren().size() - 100);
            }
            messageContainer.getChildren().clear();
        }

        messageSnapshot.clear(); 

        enableAutoScroll();

        if (connection != null && connection.isAlive()) {
            try {
                connection.history(currentUser.getUsername(), u.getUsername(), 50);
            } catch (Exception e) {
                System.err.println("[HISTORY] Failed to load history: " + e.getMessage());
                Platform.runLater(() -> showErrorAlert("Lịch sử tin nhắn không tải được: " + e.getMessage()));
            }
        }

        this.currentPeerUser = u;
        List<Frame> pending = pendingFileEvents.remove(u.getUsername());
        if (pending != null) pending.forEach(this::handleServerFrame);
    }
    
    private void snapshotText(String text, boolean incoming) {
        if (text == null) return;
        messageSnapshot.add(new MsgView(System.currentTimeMillis(), incoming, text));
    }

    
    private void handleServerFrame(Frame f) {
        new MessageHandler(this).handleServerFrame(f);
    }

    public HBox findRowByUserData(String id) {
        if (id == null) return null;
        for (Node n : messageContainer.getChildren()) {
            if (n instanceof HBox h) {
                Object ud = h.getUserData();
                if (ud != null && id.equals(String.valueOf(ud))) return h;
            }
        }
        return null;
    }

    public boolean removeMessageById(String id) {
        HBox row = findRowByUserData(id);
        return row != null && messageContainer.getChildren().remove(row);
    }

    public void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void onSendMessage() {
        new MessageHandler(this).onSendMessage();
    }

    private ScrollPane findMessageScrollPane() {
        if (messageContainer == null) return null;
        Node p = messageContainer.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        return (p instanceof ScrollPane sp) ? sp : null;
    }

    private void enableAutoScroll() {
        ScrollPane sp = findMessageScrollPane();
        if (sp == null) return;

        if (autoScrollListener != null) {
            messageContainer.heightProperty().removeListener(autoScrollListener);
        }

        autoScrollListener = (obs, oldV, newV) -> Platform.runLater(() -> {
            sp.layout();
            sp.setVvalue(1.0);
        });
        messageContainer.heightProperty().addListener(autoScrollListener);

        Platform.runLater(() -> {
            sp.layout();
            sp.setVvalue(1.0);
        });
    }
    
    public void updateTextBubbleById(String id, String newText) {
        HBox row = findRowByUserData(id);
        if (row == null) return;
        Node bubble = extractBubble(row);
        if (!(bubble instanceof VBox vb)) return;
        // Chỉ áp dụng cho bubble text
        String bubbleId = (vb).getId();
        if (!"incoming-text".equals(bubbleId) && !"outgoing-text".equals(bubbleId)) return;

        for (Node n : vb.getChildren()) {
            if (n instanceof Label lbl) {
                lbl.setText(newText);
                break;
            }
        }
    }

    private Node extractBubble(HBox row) {
        if (row.getChildren().isEmpty()) return null;
        Node first = row.getChildren().get(0);
        Node last  = row.getChildren().get(row.getChildren().size() - 1);
        if (first instanceof VBox) return first;
        if (last instanceof VBox)  return last;
        return first; 
    }

    
    public void enqueuePendingOutgoing(HBox row) {
        if (row != null) pendingOutgoingTexts.addLast(row);
    }
    public void tagNextPendingOutgoing(String id) {
        HBox row = pendingOutgoingTexts.pollFirst();
        if (row != null && id != null) row.setUserData(id);
    }

    public HBox addTextMessage(String text, boolean incoming) {
        HBox row = addTextMessage(text, incoming, null);
        snapshotText(text, incoming);
        return row;
    }
    public HBox addImageMessage(Image img, String caption, boolean incoming) {
        return addImageMessage(img, caption, incoming, null);
    }
    public HBox addFileMessage(String filename, String meta, boolean incoming) {
        return addFileMessage(filename, meta, incoming, null);
    }

    public HBox addVoiceMessage(String duration, boolean incoming, String fileId) {
        return new UIMessageHandler(this).addVoiceMessage(duration, incoming, fileId);
    }
    public HBox addVideoMessage(String filename, String meta, boolean incoming, String fileId) {
        return new UIMessageHandler(this).addVideoMessage(filename, meta, incoming, fileId);
    }

    public HBox addTextMessage(String text, boolean incoming, String messageId) {
        HBox row = new UIMessageHandler(this).addTextMessage(text, incoming, messageId);
        snapshotText(text, incoming);
        return row;
    }
    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId) {
        return new UIMessageHandler(this).addImageMessage(img, caption, incoming, messageId);
    }
    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId) {
        return new UIMessageHandler(this).addFileMessage(filename, meta, incoming, messageId);
    }

    public void showOutgoingFile(String filename, String mime, long bytes, String fileId, String duration) {
        new FileHandler(this).showOutgoingFile(filename, mime, bytes, fileId, duration);
    }

    public void updateVoiceBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateVoiceBubbleFromUrl(row, fileUrl);
    }

    public void updateVideoBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateVideoBubbleFromUrl(row, fileUrl);
    }

    public void updateImageBubbleFromUrl(HBox row, String fileUrl) {
        new MediaHandler(this).updateImageBubbleFromUrl(row, fileUrl);
    }

    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        new UIMessageHandler(this).applyStatusLabel(lbl, online, lastSeenIso);
    }

    public String humanize(String iso, boolean withDot) {
        return new UtilHandler().humanize(iso, withDot);
    }

    private static String humanBytes(long v) {
        return UtilHandler.humanBytes(v);
    }

    private static String formatDuration(int sec) {
        return UtilHandler.formatDuration(sec);
    }

    private static client.controller.mid.UtilHandler.MediaKind classifyMedia(String mime, String name) {
        return UtilHandler.classifyMedia(mime, name);
    }

    private static String jsonGet(String json, String key) {
        return UtilHandler.jsonGet(json, key);
    }

    private static String guessExt(String mime, String fallbackName) {
        return UtilHandler.guessExt(mime, fallbackName);
    }

    public void callCurrentPeer() {
        new CallHandler(this).callCurrentPeer();
    }

    public void setCallService(CallSignalingService svc) { this.callSvc = svc; }

    public void startCallTo(User peerUser) {
        new CallHandler(this).startCallTo(peerUser);
    }

    @Override
    public void onInvite(String fromUser, String callId) {
        new CallHandler(this).onInvite(fromUser, callId);
    }
    @Override
    public void onAccept(String fromUser, String callId) {
        new CallHandler(this).onAccept(fromUser, callId);
    }
    @Override
    public void onReject(String fromUser, String callId) {
        new CallHandler(this).onReject(fromUser, callId);
    }
    @Override
    public void onCancel(String fromUser, String callId) {
        new CallHandler(this).onCancel(fromUser, callId);
    }
    @Override
    public void onBusy(String fromUser, String callId) {
        new CallHandler(this).onBusy(fromUser, callId);
    }
    @Override
    public void onEnd(String fromUser, String callId) {
        new CallHandler(this).onEnd(fromUser, callId);
    }
    @Override
    public void onOffline(String toUser, String callId) {
        new CallHandler(this).onOffline(toUser, callId);
    }
    @Override
    public void onOffer(String fromUser, String callId, String sdpJson) {
        new CallHandler(this).onOffer(fromUser, callId, sdpJson);
    }
    @Override
    public void onAnswer(String fromUser, String callId, String sdpJson) {
        new CallHandler(this).onAnswer(fromUser, callId, sdpJson);
    }
    @Override
    public void onIce(String from, String id, String c) {
        new CallHandler(this).onIce(from, id, c);
    }

    private void openCallWindow(String peer, String callId, VideoCallController.Mode mode) {
        new CallHandler(this).openCallWindow(peer, callId, mode);
    }

    private void closeCallWindow() {
        new CallHandler(this).closeCallWindow();
    }

    public void showVoiceRecordDialog(Window owner, AudioFormat format, File audioFile, Consumer<byte[]> onComplete) {
        voiceRecordHandler.showVoiceRecordDialog(owner, format, audioFile, onComplete);
    }

    public Label getCurrentChatName() { return currentChatName; }
    public Label getCurrentChatStatus() { return currentChatStatus; }
    public VBox getMessageContainer() { return messageContainer; }
    public TextField getMessageField() { return messageField; }
    public Button getLogoutBtn() { return logoutBtn; }
    public RightController getRightController() { return rightController; }
    public CallSignalingService getCallSvc() { return callSvc; }
    public Stage getCallStage() { return callStage; }
    public VideoCallController getCallCtrl() { return callCtrl; }
    public String getCurrentCallId() { return currentCallId; }
    public String getCurrentPeer() { return currentPeer; }
    public boolean isCaller() { return isCaller; }
    public User getCurrentUser() { return currentUser; }
    public User getSelectedUser() { return selectedUser; }
    public ClientConnection getConnection() { return connection; }
    public User getCurrentPeerUser() { return currentPeerUser; }
    public LanVideoSession getVideoSession() { return videoSession; }
    public LanAudioSession getAudioSession() { return audioSession; }
    public Map<String, List<Frame>> getPendingFileEvents() { return pendingFileEvents; }
    public Map<String, String> getFileIdToName() { return fileIdToName; }
    public Map<String, String> getFileIdToMime() { return fileIdToMime; }
    public Map<String, HBox> getOutgoingFileBubbles() { return outgoingFileBubbles; }
    public Map<String, BufferedOutputStream> getDlOut() { return dlOut; }
    public Map<String, File> getDlPath() { return dlPath; }

    public void setCallStage(Stage callStage) { this.callStage = callStage; }
    public void setCallCtrl(VideoCallController callCtrl) { this.callCtrl = callCtrl; }
    public void setCurrentCallId(String currentCallId) { this.currentCallId = currentCallId; }
    public void setCurrentPeer(String currentPeer) { this.currentPeer = currentPeer; }
    public void setCaller(boolean isCaller) { this.isCaller = isCaller; }
    public void setCurrentPeerUser(User currentPeerUser) { this.currentPeerUser = currentPeerUser; }
    public void setVideoSession(LanVideoSession videoSession) { this.videoSession = videoSession; }
    public void setAudioSession(LanAudioSession audioSession) { this.audioSession = audioSession; }
}

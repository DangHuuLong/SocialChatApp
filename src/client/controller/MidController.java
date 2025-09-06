package client.controller;

import client.ClientConnection;
import client.model.User;
import client.signaling.CallSignalListener;
import client.signaling.CallSignalingService;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import server.dao.UserDAO;

import java.sql.SQLException;
import java.time.Instant;

public class MidController implements CallSignalListener {
    private Label currentChatName;
    private Label currentChatStatus;
    private VBox messageContainer;
    private TextField messageField;
    private Button logoutBtn;

    private RightController rightController;

    private CallSignalingService callSvc;
    private StackPane overlayHost; 
    private Node overlayRoot;
    private Stage callStage; 
    private VideoCallController callCtrl; 
    private String currentCallId;
    private String currentPeer;
    private boolean isCaller = false;

    // State / net
    private User currentUser;
    private User selectedUser;
    private ClientConnection connection;

    public void bind(Label currentChatName, Label currentChatStatus,
                     VBox messageContainer, TextField messageField, Button logoutBtn) {
        this.currentChatName = currentChatName;
        this.currentChatStatus = currentChatStatus;
        this.messageContainer = messageContainer;
        this.messageField = messageField;
        this.logoutBtn = logoutBtn;

        // event: enter để gửi
        if (this.messageField != null) {
            this.messageField.setOnAction(e -> onSendMessage());
        }
        // event: logout
        if (this.logoutBtn != null) {
            this.logoutBtn.setOnAction(e -> onLogout());
        }
    }

    public void setRightController(RightController rc) { this.rightController = rc; }

    public void setCurrentUser(User user) { this.currentUser = user; }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (this.connection != null) {
            this.connection.startListener(
                msg -> Platform.runLater(() -> handleServerMessage(msg)),
                err -> System.err.println("[NET] Disconnected: " + err)
            );
        }
    }

    // ===== Open conversation =====
    public void openConversation(User u) {
        this.selectedUser = u;
        if (currentChatName != null) currentChatName.setText(u.getUsername());

        try {
            UserDAO.Presence p = UserDAO.getPresence(u.getId());
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;

            applyStatusLabel(currentChatStatus, online, lastSeen);
            if (rightController != null) {
                rightController.showUser(u, online, lastSeen);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            if (rightController != null) rightController.showUser(u, false, null);
        }

        if (messageContainer != null) messageContainer.getChildren().clear();
        if (connection != null && connection.isAlive()) {
            connection.send("HISTORY " + u.getUsername() + " 50");
        }
    }

    // ===== Messaging =====
    public void onSendMessage() {
        if (messageField == null) return;
        String text = messageField.getText().trim();
        if (text.isEmpty() || selectedUser == null) return;

        if (connection != null && connection.isAlive()) {
            connection.sendDirectMessage(selectedUser.getUsername(), text);
        }
        addTextMessage(text, false);
        messageField.clear();
    }

    private void handleServerMessage(String msg) {
        if (msg == null || msg.isBlank()) return;
        msg = msg.trim();

        String openPeer = (selectedUser != null) ? selectedUser.getUsername() : null;

        if (msg.startsWith("[DM]")) {
            String payload = msg.substring(4).trim();
            int p = payload.indexOf(": ");
            if (p > 0) {
                String sender = payload.substring(0, p);
                String body   = payload.substring(p + 2);
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);
                }
            }
            return;
        }

        if (msg.startsWith("[HIST IN]")) {
            String payload = msg.substring(9).trim();
            int p = payload.indexOf(": ");
            if (p > 0) {
                String sender = payload.substring(0, p);
                String body   = payload.substring(p + 2);
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);
                }
            }
            return;
        }

        if (msg.startsWith("[HIST OUT]")) {
            String body = msg.substring(10).trim();
            addTextMessage(body, false);
        }
        // các message khác bỏ qua
    }

    // ===== UI bubbles =====
    public void addTextMessage(String text, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(bubble, spacer);
        else          row.getChildren().addAll(spacer, bubble);

        messageContainer.getChildren().add(row);
    }

    public void addImageMessage(Image img, String caption, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        Label cap = new Label(caption);
        box.getChildren().addAll(iv, cap);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(box, spacer);
        else          row.getChildren().addAll(spacer, box);

        messageContainer.getChildren().add(row);
    }

    public void addFileMessage(String filename, String meta, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new javafx.geometry.Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-name");

        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.getChildren().addAll(nameLbl, metaLbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button btn = new Button(incoming ? "Tải" : "Mở");

        content.getChildren().addAll(icon, info, spacer, btn);
        box.getChildren().add(content);

        if (incoming) row.getChildren().addAll(box, new Region());
        else          row.getChildren().addAll(new Region(), box);

        messageContainer.getChildren().add(row);
    }

    public void addVoiceMessage(String duration, boolean incoming) {
        HBox row = new HBox(6);
        row.setAlignment(incoming ? javafx.geometry.Pos.CENTER_LEFT : javafx.geometry.Pos.CENTER_RIGHT);

        HBox voiceBox = new HBox(10);
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
        voiceBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button playBtn = new Button(incoming ? "▶" : "⏸");
        playBtn.getStyleClass().add("audio-btn");

        Slider slider = new Slider();
        slider.setPrefWidth(200);
        if (!incoming) slider.setValue(35);

        Label dur = new Label(duration);

        voiceBox.getChildren().addAll(playBtn, slider, dur);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(voiceBox, spacer);
        else          row.getChildren().addAll(spacer, voiceBox);

        messageContainer.getChildren().add(row);
    }

    // ===== Logout =====
    private void onLogout() {
        try {
            if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false);
        } catch (SQLException ignored) {}

        if (connection != null) {
            try { connection.send("QUIT"); } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        currentUser = null;

        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ===== Util =====
    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private String humanize(String iso, boolean withDot) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            var d = java.time.Duration.between(t, Instant.now());
            long m = d.toMinutes();
            String p;
            if (m < 1) p = "just now";
            else if (m < 60) p = m + "m ago";
            else {
                long h = m / 60;
                p = (h < 24) ? (h + "h ago") : ((h / 24) + "d ago");
            }
            return withDot ? " • " + p : p;
        } catch (Exception e) {
            return withDot ? " • " + iso : iso;
        }
    }
    
    public void setCallService(CallSignalingService svc) {
        this.callSvc = svc;
    }
    
    /** HomeController gọi để đưa centerStack cho Mid làm host overlay. */
    public void setOverlayHost(StackPane host) {
        this.overlayHost = host;
    }

    /** (Tuỳ chọn) Gọi từ UI khi bạn muốn chủ động gọi đi */
    public void startCallTo(client.model.User peerUser) {
        String peer = peerUser.getUsername();
        String callId = java.util.UUID.randomUUID().toString();
        currentPeer = peer; currentCallId = callId; isCaller = true;

        openCallWindow(peer, callId, VideoCallController.Mode.OUTGOING);
        callSvc.sendInvite(peer, callId);
    }


    @Override public void onInvite(String fromUser, String callId) {
        Platform.runLater(() -> {
            currentPeer = fromUser; currentCallId = callId; isCaller = false;
            openCallWindow(fromUser, callId, VideoCallController.Mode.INCOMING);
        });
    }

    @Override public void onAccept(String fromUser, String callId) {
        Platform.runLater(() -> {
            if (isCaller && callCtrl != null
                && fromUser.equals(currentPeer) && callId.equals(currentCallId)) {
                callCtrl.setMode(VideoCallController.Mode.CONNECTING);
            }
        });
    }

    @Override public void onReject(String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }
    @Override public void onCancel(String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }
    @Override public void onBusy  (String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }
    @Override public void onEnd   (String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }

    @Override public void onOffline(String toUser, String callId) {
        // Người nhận offline -> không mở cửa sổ; có thể show toast nếu muốn
    }

    // GĐ4 media sẽ xử lý 3 cái dưới; hiện tại chỉ log:
    @Override public void onOffer (String from, String id, String sdp) { System.out.println("[CALL] OFFER len=" + sdp.length()); }
    @Override public void onAnswer(String from, String id, String sdp){ System.out.println("[CALL] ANSWER len=" + sdp.length()); }
    @Override public void onIce   (String from, String id, String c)  { System.out.println("[CALL] ICE len=" + c.length()); }

    private void openCallWindow(String peer, String callId, VideoCallController.Mode mode) {
        try {
            // đóng phiên cũ nếu còn
            if (callStage != null) { callStage.close(); callStage = null; }

            FXMLLoader fx = new FXMLLoader(getClass().getResource("/client/view/VideoCallOverlay.fxml"));
            Parent root = fx.load();
            callCtrl = fx.getController();
            callCtrl.init(callSvc, peer, callId, mode, this::closeCallWindow);

            callStage = new Stage(StageStyle.UTILITY); // có khung nhỏ gọn; dùng UNDECORATED nếu muốn phẳng.
            callStage.setTitle("Call • @" + peer);
            callStage.initModality(Modality.NONE);     // không khoá cửa sổ chat
            callStage.setResizable(false);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            callStage.setScene(scene);
            callStage.setAlwaysOnTop(true);
            callStage.show();
            callStage.requestFocus();

            // Người dùng bấm nút [X] của cửa sổ → gửi END
            callStage.setOnCloseRequest(ev -> {
                if (currentCallId != null && currentPeer != null) {
                    callSvc.sendEnd(currentPeer, currentCallId);
                }
                resetCallState();
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void closeCallWindow() {
        if (callStage != null) {
            callStage.close();
            callStage = null;
        }
        resetCallState();
    }

    private void resetCallState() {
        callCtrl = null;
        currentCallId = null;
        currentPeer = null;
        isCaller = false;
    }
    
    public void showCallWindow() { if (callStage != null) { callStage.show(); callStage.toFront(); } }
    public void hideCallWindow() { if (callStage != null) callStage.hide(); }

}

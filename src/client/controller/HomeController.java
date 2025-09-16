package client.controller;

import client.ClientConnection;
import client.signaling.CallSignalingService;
import common.Frame;
import common.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import server.dao.UserDAO;

import java.io.File;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

public class HomeController {
    @FXML private VBox chatList;
    @FXML private TextField searchField;
    @FXML private Label currentChatName;
    @FXML private Label currentChatStatus;
    @FXML private VBox messageContainer;
    @FXML private TextField messageField;
    @FXML private Button logoutBtn;
    @FXML private Label infoName;
    @FXML private Label chatStatus;
    @FXML private StackPane centerStack;
    @FXML private VBox centerContent;
    @FXML private VBox centerEmpty;
    @FXML private StackPane rightStack;
    @FXML private VBox rightContent;
    @FXML private VBox rightEmpty;
    @FXML private Button callBtn;
    @FXML private Button videoBtn;

    private final LeftController leftCtrl = new LeftController();
    private final MidController midCtrl = new MidController();
    private final RightController rightCtrl = new RightController();

    private User currentUser;
    private ClientConnection connection;
    private CallSignalingService callSvc;
    private String currentPeerUsername = null;

    @FXML
    private void initialize() {
        leftCtrl.bind(chatList, searchField);
        rightCtrl.bind(infoName, chatStatus);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField, logoutBtn);
        midCtrl.setRightController(rightCtrl);

        leftCtrl.setOnOpenConversation(user -> {
            currentPeerUsername = (user != null ? user.getUsername() : null);
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            midCtrl.openConversation(user);
            System.out.println("[LEFT] callback registered");
        });

        toggleCenterEmpty(true);
        toggleRightEmpty(true);
        System.out.println("[INIT] setOnOpenConversation");
    }

    private void toggleCenterEmpty(boolean showEmpty) {
        centerEmpty.setVisible(showEmpty);
        centerEmpty.setManaged(showEmpty);
        centerContent.setVisible(!showEmpty);
        centerContent.setManaged(!showEmpty);
    }

    private void toggleRightEmpty(boolean showEmpty) {
        rightEmpty.setVisible(showEmpty);
        rightEmpty.setManaged(showEmpty);
        rightContent.setVisible(!showEmpty);
        rightContent.setManaged(!showEmpty);
    }

    public void setCallService(CallSignalingService svc) {
        this.callSvc = svc;
        this.callSvc.setListener(midCtrl);
        midCtrl.setCallService(this.callSvc);
    }

    public void onServerLine(String line) { /* không dùng nữa */ }
    public void onConnectionError(Exception e) { /* optional */ }

    public void setCurrentUser(User user) {
        this.currentUser = user;
        leftCtrl.setCurrentUser(user);
        midCtrl.setCurrentUser(user);
        leftCtrl.reloadAll();
    }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        midCtrl.setConnection(conn);
    }

    public void reloadAll() { leftCtrl.reloadAll(); }
    public void searchUsers() { leftCtrl.searchUsers(searchField.getText()); }

    @FXML
    private void onSend() {
        if (midCtrl != null) midCtrl.onSendMessage();
    }

    @FXML
    private void onAttach() {
        if (connection == null || !connection.isAlive()) {
            System.out.println("[ATTACH] Chưa kết nối server.");
            return;
        }
        if (currentUser == null) {
            System.out.println("[ATTACH] Chưa đăng nhập.");
            return;
        }
        final String toUser = currentPeerUsername;
        if (toUser == null || toUser.isBlank()) {
            System.out.println("[ATTACH] Chưa chọn đoạn chat / không xác định người nhận.");
            return;
        }

        javafx.stage.FileChooser fc = new javafx.stage.FileChooser();
        fc.setTitle("Chọn file để gửi");
        fc.getExtensionFilters().addAll(
            new javafx.stage.FileChooser.ExtensionFilter("Tất cả", "*.*"),
            new javafx.stage.FileChooser.ExtensionFilter("Ảnh", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp"),
            new javafx.stage.FileChooser.ExtensionFilter("Âm thanh", "*.mp3", "*.m4a", "*.aac", "*.wav", "*.ogg"),
            new javafx.stage.FileChooser.ExtensionFilter("Video", "*.mp4", "*.mov", "*.mkv", "*.webm")
        );

        javafx.stage.Stage stage = (javafx.stage.Stage) centerStack.getScene().getWindow();
        java.io.File file = fc.showOpenDialog(stage);
        if (file == null) {
            System.out.println("[ATTACH] Người dùng huỷ chọn file.");
            return;
        }

        final String fromUser = currentUser.getUsername();
        final String fileId = java.util.UUID.randomUUID().toString();
        System.out.println("[ATTACH] Gửi file: " + file.getAbsolutePath() + " -> @" + toUser + ", fileId=" + fileId);

        Thread t = new Thread(() -> {
            try {
                // KHÔNG vẽ UI ở đây nữa (tránh bubble đầu tiên)
                String mime = client.ClientConnection.guessMime(file);
                common.Frame ackF = connection.sendFileWithAck(fromUser, toUser, file, mime, fileId, 15_000);
                System.out.println("[ATTACH] ACK(tid=" + ackF.transferId + "): " + ackF.body);
            } catch (java.util.concurrent.TimeoutException te) {
                System.out.println("[ATTACH] TIMEOUT đợi ACK.");
            } catch (Exception e) {
                System.out.println("[ATTACH] Lỗi gửi file: " + e.getMessage());
                e.printStackTrace(System.out);
            }
        }, "send-file");
        t.setDaemon(true);
        t.start();
    }

    @FXML
    private void onVoice() {
        System.out.println("[VOICE] Chưa triển khai. Sẽ thu âm & gửi sau.");
    }

    @FXML
    private void onLogout() {
        try {
            if (currentUser != null) {
                UserDAO.setOnline(currentUser.getId(), false);
            }
        } catch (SQLException ignored) {}

        leftCtrl.stopPolling();

        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }

        currentUser = null;

        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();
            Scene scene = new Scene(root);
            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @FXML
    private void onCall() {
        if (midCtrl != null) {
            midCtrl.callCurrentPeer();
        }
    }

    private static String humanBytes(long v) {
        if (v <= 0) return "0 B";
        final String[] u = {"B","KB","MB","GB","TB"};
        int i = 0;
        double d = v;
        while (d >= 1024 && i < u.length - 1) { d /= 1024.0; i++; }
        return (d >= 10 ? String.format("%.0f %s", d, u[i]) : String.format("%.1f %s", d, u[i]));
    }
}
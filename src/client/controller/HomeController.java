// client/controller/HomeController.java
package client.controller;

import client.ClientConnection;
import client.model.User;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.*;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import server.dao.UserDAO;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

public class HomeController {

    @FXML private VBox chatList;
    @FXML private Button logoutBtn;

    @FXML private Label currentChatName;
    @FXML private Label currentChatStatus;

    @FXML private Label infoName;
    @FXML private Label chatStatus;

    @FXML private VBox messageContainer;
    @FXML private TextField messageField;

    private User currentUser;
    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private final Map<Integer, User> idToUser = new HashMap<>();
    private Map<Integer, UserDAO.Presence> lastPresence = new HashMap<>();

    private Timeline poller;
    private Integer selectedUserId = null;

    private ClientConnection connection;

    public void setCurrentUser(User user) { this.currentUser = user; }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (connection != null) {
            connection.startListener(
                msg -> Platform.runLater(() -> handleServerMessage(msg)),
                err -> System.err.println("[NET] Disconnected: " + err)
            );
        }
    }

    // ========== Users / Presence ==========
    public void loadUsers() {
        if (currentUser == null) return;
        try {
            List<User> others = UserDAO.listOthers(currentUser.getId());
            chatList.getChildren().clear();
            lastLabels.clear();
            idToUser.clear();

            for (User u : others) {
                idToUser.put(u.getId(), u);
                chatList.getChildren().add(createChatItem(u));
            }
            startPollingPresence();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private HBox createChatItem(User u) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-item");
        row.setPadding(new Insets(8, 8, 8, 8));
        row.setUserData(u.getId());

        ImageView avatar = new ImageView(new Image(
            getClass().getResource("/client/view/images/default user.png").toExternalForm()
        ));
        avatar.setFitWidth(40);
        avatar.setFitHeight(40);
        avatar.setPreserveRatio(true);

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label name = new Label(u.getUsername());
        name.getStyleClass().add("chat-name");

        Label last = new Label("Offline");
        last.getStyleClass().addAll("chat-last", "chat-status-offline");
        lastLabels.put(u.getId(), last);

        textBox.getChildren().addAll(name, last);
        row.getChildren().addAll(avatar, textBox);

        row.setOnMouseClicked(ev -> {
            Integer uid = (Integer) row.getUserData();
            if (uid != null) openConversation(uid);
        });
        return row;
    }

    private void openConversation(int userId) {
        selectedUserId = userId;
        User u = idToUser.get(userId);
        if (u == null) return;

        currentChatName.setText(u.getUsername());
        infoName.setText(u.getUsername());

        try {
            UserDAO.Presence p = UserDAO.getPresence(userId);
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;
            applyStatusLabel(currentChatStatus, online, lastSeen);
            applyStatusLabel(chatStatus,        online, lastSeen);
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            applyStatusLabel(chatStatus, false, null);
        }

        messageContainer.getChildren().clear();
        if (connection != null && connection.isAlive()) {
            connection.send("HISTORY " + u.getUsername() + " 50");
        }
    }


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

    @FXML
    private void onLogout() {
        // Cập nhật trạng thái offline trong DB
        try {
            if (currentUser != null) {
                UserDAO.setOnline(currentUser.getId(), false);
            }
        } catch (SQLException ignored) {}

        // Dừng polling và đóng kết nối
        stopPolling();
        if (connection != null) {
            try {
                connection.send("QUIT"); // gửi tín hiệu cho server
            } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        currentUser = null;

        // Quay về màn hình đăng nhập
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


    private void startPollingPresence() {
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.seconds(3), e -> refreshPresenceOnce()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        refreshPresenceOnce();
    }

    private void stopPolling() {
        if (poller != null) { poller.stop(); poller = null; }
    }

    private void refreshPresenceOnce() {
        try {
            Map<Integer, UserDAO.Presence> map = UserDAO.getPresenceOfAll();
            lastPresence = map;
            Platform.runLater(() -> {
                for (var entry : lastLabels.entrySet()) {
                    int userId = entry.getKey();
                    Label lbl = entry.getValue();
                    UserDAO.Presence p = map.get(userId);
                    if (p == null) continue;

                    lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
                    if (p.online) {
                        lbl.setText("Online");
                        lbl.getStyleClass().add("chat-status-online");
                    } else {
                        lbl.setText("Offline • " + humanize(p.lastSeenIso, false));
                        lbl.getStyleClass().add("chat-status-offline");
                    }
                }
            });
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    // ========== Messaging ==========
    @FXML
    private void initialize() {
        messageField.setOnAction(e -> onSendMessage());
    }

    @FXML
    private void onSendMessage() {
        String text = messageField.getText().trim();
        if (text.isEmpty() || selectedUserId == null) return;

        if (connection != null && connection.isAlive()) {
            User target = idToUser.get(selectedUserId);
            if (target != null) {
                connection.sendDirectMessage(target.getUsername(), text);
            }
        }
        addTextMessage(text, false);
        messageField.clear();
    }

    private void handleServerMessage(String msg) {
        if (msg == null || msg.isBlank()) return;
        msg = msg.trim();

        // Tên user đang mở
        String openPeer = null;
        if (selectedUserId != null) {
            User u = idToUser.get(selectedUserId);
            if (u != null) openPeer = u.getUsername();
        }

        if (msg.startsWith("[DM]")) {
            // "[DM] Alice: hello world"
            String payload = msg.substring(4).trim();
            int p = payload.indexOf(": ");
            if (p > 0) {
                String sender = payload.substring(0, p);
                String body   = payload.substring(p + 2);
                // Chỉ render nếu đúng đoạn chat đang mở
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);   // <-- chỉ hiển thị nội dung, không kèm "Alice: "
                }
            }
            return;
        }

        if (msg.startsWith("[HIST IN]")) {
            // "[HIST IN] Alice: hello"
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
            // "[HIST OUT] hello"
            String body = msg.substring(10).trim();
            addTextMessage(body, false);
            return;
        }

        // Bỏ qua các message khác (OK/ERR/HELLO/ONLINE/BYE...)
    }



    // ========== UI bubbles ==========
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
        box.setId(incoming ? "incoming-image" : "outgoing-image"); // dùng ID để match CSS

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
        box.setId(incoming ? "incoming-file" : "outgoing-file"); // dùng ID
        box.setPadding(new Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-name"); // để ăn rule .file-name

        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("meta");      // để ăn rule .meta

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
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice"); // dùng ID
        voiceBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);

        Button playBtn = new Button(incoming ? "▶" : "⏸");
        playBtn.getStyleClass().add("audio-btn"); // để ăn rule .audio-btn trong CSS

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

}

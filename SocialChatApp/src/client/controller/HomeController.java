// client/controller/HomeController.java
package client.controller;

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
import javafx.util.Duration; // cho Timeline/KeyFrame

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

    private User currentUser;

    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private final Map<Integer, User> idToUser = new HashMap<>();
    private Map<Integer, UserDAO.Presence> lastPresence = new HashMap<>();

    private Timeline poller;
    private Integer selectedUserId = null; 

    public void setCurrentUser(User user) { this.currentUser = user; }

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
        row.setId("chatItem" + u.getId());
        row.setUserData(u.getId());

        ImageView avatar = new ImageView(new Image(
                getClass().getResource("/client/view/images/default user.png").toExternalForm()
        ));
        avatar.setFitWidth(40);
        avatar.setFitHeight(40);
        avatar.setPreserveRatio(true);
        avatar.setPickOnBounds(true);

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

        if (currentChatName != null) currentChatName.setText(u.getUsername());
        if (infoName != null) infoName.setText(u.getUsername());

        UserDAO.Presence p = lastPresence.get(userId);
        boolean online = p != null && p.online;
        String lastSeen = (p != null) ? p.lastSeenIso : null;

        applyStatusLabel(currentChatStatus, online, lastSeen);
        applyStatusLabel(chatStatus,        online, lastSeen);
    }

    private void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + humanizeSuffix(lastSeenIso));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }

    private String humanizeSuffix(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            java.time.Duration d = java.time.Duration.between(t, Instant.now());
            long m = d.toMinutes();
            if (m < 1) return " • just now";
            if (m < 60) return " • " + m + "m ago";
            long h = m / 60;
            if (h < 24) return " • " + h + "h ago";
            long days = h / 24;
            return " • " + days + "d ago";
        } catch (Exception e) {
            return " • " + iso;
        }
    }

    @FXML
    private void onLogout() {
        try { if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false); }
        catch (SQLException ignored) {}
        stopPolling();
        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (Exception e) { e.printStackTrace(); }
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
                        lbl.setText("Offline • " + humanizeSuffixNoDot(p.lastSeenIso));
                        lbl.getStyleClass().add("chat-status-offline");
                    }
                }
                if (selectedUserId != null) {
                    UserDAO.Presence p = map.get(selectedUserId);
                    boolean online = p != null && p.online;
                    String lastSeen = (p != null) ? p.lastSeenIso : null;
                    applyStatusLabel(currentChatStatus, online, lastSeen);
                    applyStatusLabel(chatStatus,        online, lastSeen);
                }
            });
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private String humanizeSuffixNoDot(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            java.time.Duration d = java.time.Duration.between(t, Instant.now());
            long m = d.toMinutes();
            if (m < 1) return "just now";
            if (m < 60) return m + "m ago";
            long h = m / 60;
            if (h < 24) return h + "h ago";
            long days = h / 24;
            return days + "d ago";
        } catch (Exception e) {
            return iso;
        }
    }
}

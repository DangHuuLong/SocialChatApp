package client.controller;

import client.model.User;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import server.dao.UserDAO;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class LeftController {
    // FXML nodes (được bind từ HomeController)
    private VBox chatList;
    private TextField searchField;

    // State riêng của panel left
    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private final Map<Integer, User> idToUser = new HashMap<>();
    private Timeline poller;

    // User hiện tại
    private User currentUser;

    // callback khi click 1 đoạn chat
    private Consumer<User> onOpenConversation;

    // ===== Wiring từ HomeController =====
    public void bind(VBox chatList, TextField searchField) {
        this.chatList = chatList;
        this.searchField = searchField;
        
        if (this.searchField != null) {
            this.searchField.textProperty().addListener((obs, oldVal, newVal) -> {
                searchUsers(newVal);
            });
        }
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    public void setOnOpenConversation(Consumer<User> cb) {
        this.onOpenConversation = cb;
    }

    // ===== Users / Presence list =====
    private void renderUsers(List<User> users) {
        chatList.getChildren().clear();
        lastLabels.clear();
        idToUser.clear();

        for (User u : users) {
            idToUser.put(u.getId(), u);
            chatList.getChildren().add(createChatItem(u));
        }
    }
    
    public void reloadAll() {
        if (currentUser == null || chatList == null) return;
        try {
            List<User> others = UserDAO.listOthers(currentUser.getId());
            renderUsers(others);
            // đảm bảo polling đang chạy
            if (poller == null) startPollingPresence();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }
    
    public void searchUsers(String keyword) {
        if (currentUser == null) return;
        keyword = keyword == null ? "" : keyword.trim();
        if (keyword.isEmpty()) {
            reloadAll();                  
        } else {
            try {
                List<User> res = UserDAO.searchUsers(keyword, currentUser.getId()); 
                renderUsers(res);           
                if (poller == null) startPollingPresence();
            } catch (SQLException e) {
                e.printStackTrace();
            }
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
            if (uid != null && onOpenConversation != null) {
                User target = idToUser.get(uid);
                if (target != null) onOpenConversation.accept(target);
            }
        });
        return row;
    }

    public void startPollingPresence() {
        stopPolling();
        poller = new Timeline(new KeyFrame(Duration.seconds(3), e -> refreshPresenceOnce()));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        refreshPresenceOnce();
    }

    public void stopPolling() {
        if (poller != null) { poller.stop(); poller = null; }
    }

    private void refreshPresenceOnce() {
        try {
            Map<Integer, UserDAO.Presence> map = UserDAO.getPresenceOfAll();
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

    // Util
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
}

// client/controller/HomeController.java
package client.controller;

import client.model.User;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import server.dao.UserDAO;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HomeController {

    @FXML private VBox chatList;
    @FXML private Button logoutBtn;
    @FXML private Label currentChatName;
    @FXML private Label infoName;

    private User currentUser;

    // Lưu label "last" của từng user để cập nhật nhanh
    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private Timeline poller;

    public void setCurrentUser(User user) { this.currentUser = user; }

    public void loadUsers() {
        if (currentUser == null) return;
        try {
            List<User> others = UserDAO.listOthers(currentUser.getId());
            chatList.getChildren().clear();
            lastLabels.clear();
            for (User u : others) {
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

        Label last = new Label("Offline");              // mặc định
        last.getStyleClass().addAll("chat-last", "chat-status-offline");
        lastLabels.put(u.getId(), last);

        textBox.getChildren().addAll(name, last);
        row.getChildren().addAll(avatar, textBox);

        row.setOnMouseClicked(ev -> openTheConversation(name.getText()));
        return row;
    }

    private void openTheConversation(String name) {
        if (currentChatName != null) currentChatName.setText(name);
        if (infoName != null) infoName.setText(name);
    }

    @FXML
    private void onLogout() {
        try {
            if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false);
        } catch (SQLException ignored) {}
        stopPolling();
        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
            stage.setScene(new Scene(root));
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
        if (poller != null) {
            poller.stop();
            poller = null;
        }
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

                    // Xóa class cũ trước khi set class mới
                    lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");

                    if (p.online) {
                        lbl.setText("Online");
                        lbl.getStyleClass().add("chat-status-online");
                    } else {
                        lbl.setText("Offline • " + humanizeLastSeen(p.lastSeenIso));
                        lbl.getStyleClass().add("chat-status-offline");
                    }
                }
            });
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }

    private String humanizeLastSeen(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            Instant t = Instant.parse(iso);
            java.time.Duration d = java.time.Duration.between(t, Instant.now());
            long minutes = d.toMinutes(); 

            if (minutes < 1) return "just now";
            if (minutes < 60) return minutes + "m ago";
            long hours = minutes / 60;
            if (hours < 24) return hours + "h ago";
            long days = hours / 24;
            return days + "d ago";
        } catch (Exception e) {
            return iso;
        }
    }
}

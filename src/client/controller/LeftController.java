package client.controller;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;
import server.dao.UserDAO;
import javafx.geometry.Pos;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import common.User;

public class LeftController {
	private HBox leftHeader;
	private Region leftHeaderSpacer;
    private VBox sidebar;
    private VBox chatList;
    private TextField searchField;
    private Label titleLabel;
    private Button toggleSidebarBtn;
    private Button searchIconBtn;
    private Button logoutBtn;
    private Runnable onLogout;  

    private final Map<Integer, Label> lastLabels = new HashMap<>();
    private final Map<Integer, User> idToUser = new HashMap<>();
    private Timeline poller;

    private final BooleanProperty collapsed = new SimpleBooleanProperty(false);

    private User currentUser;
    private Consumer<User> onOpenConversation;

    /* ==== BIND UI ==== */
    public void bind(
            VBox sidebar,
            VBox chatList,
            TextField searchField,
            Label titleLabel,
            Button toggleSidebarBtn,
            Button searchIconBtn,
            Button logoutBtn,
            HBox leftHeader,             
            Region leftHeaderSpacer
    ) {
        this.sidebar = sidebar;
        this.chatList = chatList;
        this.searchField = searchField;
        this.titleLabel = titleLabel;
        this.toggleSidebarBtn = toggleSidebarBtn;
        this.searchIconBtn = searchIconBtn;
        this.logoutBtn = logoutBtn;
        this.leftHeader = leftHeader;       
        this.leftHeaderSpacer = leftHeaderSpacer;

        if (this.searchField != null) {
            this.searchField.textProperty().addListener((obs, o, n) -> searchUsers(n));
        }

        if (this.toggleSidebarBtn != null) {
            this.toggleSidebarBtn.setOnAction(e -> collapsed.set(!collapsed.get()));
        }
        if (this.searchIconBtn != null) {
            this.searchIconBtn.setOnAction(e -> {
                collapsed.set(false);
                Platform.runLater(() -> { if (searchField != null) searchField.requestFocus(); });
            });
        }
        
        if (this.logoutBtn != null) {
            this.logoutBtn.setOnAction(e -> { if (onLogout != null) onLogout.run(); }); // <-- thêm
        }

        collapsed.addListener((o,ov,nv) -> applyCollapsedUI(nv));
        applyCollapsedUI(collapsed.get());
    }
    
    public void setOnLogout(Runnable cb) {         
        this.onLogout = cb;
    }

    public void setCurrentUser(User user) { this.currentUser = user; }
    public void setOnOpenConversation(Consumer<User> cb) { this.onOpenConversation = cb; }

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

        // Thu nhỏ: chỉ còn avatar
        textBox.visibleProperty().bind(collapsed.not());
        textBox.managedProperty().bind(collapsed.not());

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

    public void stopPolling() { if (poller != null) { poller.stop(); poller = null; } }

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

    // === Thu/phóng UI ===
    private void applyCollapsedUI(boolean isCollapsed) {
        if (sidebar != null) {
            if (isCollapsed) {
                if (!sidebar.getStyleClass().contains("collapsed"))
                    sidebar.getStyleClass().add("collapsed");
                sidebar.setAlignment(Pos.TOP_CENTER);        
            } else {
                sidebar.getStyleClass().remove("collapsed");
                sidebar.setAlignment(Pos.TOP_LEFT);           
            }
        }

        if (leftHeader != null) {
            leftHeader.setAlignment(isCollapsed ? Pos.CENTER : Pos.CENTER_LEFT);
        }
        if (leftHeaderSpacer != null) {
            leftHeaderSpacer.setVisible(!isCollapsed);
            leftHeaderSpacer.setManaged(!isCollapsed);
            HBox.setHgrow(leftHeaderSpacer, isCollapsed ? Priority.NEVER : Priority.ALWAYS);
        }

        if (titleLabel != null) {
            titleLabel.setVisible(!isCollapsed);
            titleLabel.setManaged(!isCollapsed);
        }

        if (toggleSidebarBtn != null) {
            toggleSidebarBtn.setText(isCollapsed ? "➡️" : "⬅️");
            toggleSidebarBtn.setTooltip(new Tooltip(isCollapsed ? "Mở rộng" : "Thu gọn"));
        }

        if (searchField != null) { searchField.setVisible(!isCollapsed); searchField.setManaged(!isCollapsed); }
        if (searchIconBtn != null) { searchIconBtn.setVisible(isCollapsed); searchIconBtn.setManaged(isCollapsed); }

        if (logoutBtn != null) {
            logoutBtn.setText(isCollapsed ? "🚪" : "🚪 Logout");
            if (isCollapsed) {
                if (!logoutBtn.getStyleClass().contains("logout-compact"))
                    logoutBtn.getStyleClass().add("logout-compact");
            } else {
                logoutBtn.getStyleClass().remove("logout-compact");
            }
        }

        if (chatList != null) chatList.requestLayout();
        
        if (logoutBtn != null) {
            logoutBtn.setVisible(true);    // <-- thêm
            logoutBtn.setManaged(true);    // <-- thêm
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

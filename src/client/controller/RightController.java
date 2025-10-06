package client.controller;

import common.User;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.time.Instant;

public class RightController {
    private Label infoName;
    private Label chatStatus;
    private ImageView rightHeaderAvatar;

    public void bind(Label infoName, Label chatStatus, ImageView rightHeaderAvatar) {
        this.infoName = infoName;
        this.chatStatus = chatStatus;
        this.rightHeaderAvatar = rightHeaderAvatar;
    }

    public void showUser(User u, boolean online, String lastSeenIso) {
        if (infoName != null) infoName.setText(u.getUsername());
        applyStatusLabel(chatStatus, online, lastSeenIso);
    }
    
    public void setAvatar(Image img) {
        if (rightHeaderAvatar != null && img != null) {
            rightHeaderAvatar.setImage(img);
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
}

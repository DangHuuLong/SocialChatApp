package client.controller;

import javafx.scene.control.Label;

import java.time.Instant;

import common.User;

public class RightController {
    private Label infoName;
    private Label chatStatus;

    public void bind(Label infoName, Label chatStatus) {
        this.infoName = infoName;
        this.chatStatus = chatStatus;
    }

    // Cập nhật panel phải khi chọn 1 user
    public void showUser(User u, boolean online, String lastSeenIso) {
        if (infoName != null) infoName.setText(u.getUsername());
        applyStatusLabel(chatStatus, online, lastSeenIso);
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
}

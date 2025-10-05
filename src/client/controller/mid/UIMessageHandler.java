package client.controller.mid;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import client.controller.MidController;

public class UIMessageHandler {
    private final MidController controller;

    public UIMessageHandler(MidController controller) {
        this.controller = controller;
    }

    private void attachSideMenu(HBox row, Region spacer, boolean incoming, String messageId) {
        HBox spacerBox = new HBox();
        HBox.setHgrow(spacerBox, Priority.ALWAYS);
        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);

        StackPane holder = new StackPane();
        holder.setPickOnBounds(false);
        StackPane.setAlignment(holder, incoming ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        Button menuBtn = new Button("⋮");
        menuBtn.setFocusTraversable(false);
        menuBtn.getStyleClass().add("msg-menu");
        menuBtn.setOpacity(0);
        holder.getChildren().add(menuBtn);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(120), menuBtn);
        fadeIn.setToValue(1.0);
        FadeTransition fadeOut = new FadeTransition(Duration.millis(120), menuBtn);
        fadeOut.setToValue(0.0);

        ContextMenu cm = new ContextMenu();
        MenuItem miDelete = new MenuItem("Xóa");
        miDelete.setStyle("-fx-text-fill:#111827; -fx-font-size:12px;");
        cm.getItems().add(miDelete);

        miDelete.setOnAction(e -> {
            boolean removed = controller.getMessageContainer().getChildren().remove(row);
            Object ud = (messageId != null) ? messageId : row.getUserData();
            if (ud != null && controller.getConnection() != null) {
                try {
                    long id = Long.parseLong(String.valueOf(ud));
                    controller.getConnection().deleteMessage(id);
                } catch (Exception ignore) { }
            }
        });

        menuBtn.setOnAction(e -> cm.show(menuBtn, Side.BOTTOM, 0, 0));
        cm.setOnShowing(e -> menuBtn.setOpacity(1.0));
        cm.setOnHiding(e -> fadeOut.playFromStart());

        row.setOnMouseEntered(e -> fadeIn.playFromStart());
        row.setOnMouseExited(e -> { if (!cm.isShowing()) fadeOut.playFromStart(); });

        if (incoming) {
            spacerBox.getChildren().addAll(holder, filler);
        } else {
            spacerBox.getChildren().addAll(filler, holder);
        }

        int idx = row.getChildren().indexOf(spacer);
        if (idx >= 0) row.getChildren().set(idx, spacerBox);

        if (messageId != null) row.setUserData(messageId);
    }

    private void scrollToBottom() {
        var n = controller.getMessageContainer();
        var p = n.getParent();
        while (p != null && !(p instanceof ScrollPane)) p = p.getParent();
        if (p instanceof ScrollPane sp) {
            Platform.runLater(() -> {
                sp.layout();
                sp.setVvalue(1.0);
            });
        }
    }

    private HBox addRowWithBubble(Node bubble, boolean incoming, String messageId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }

        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(bubble, spacer);
        else          row.getChildren().addAll(spacer, bubble);

        attachSideMenu(row, spacer, incoming, messageId);

        controller.getMessageContainer().getChildren().add(row);
        scrollToBottom();
        return row;
    }

    public HBox addTextMessage(String text, boolean incoming, String messageId) {
        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);

        return addRowWithBubble(bubble, incoming, messageId);
    }

    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId) {
        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        Label cap = new Label(caption);
        box.getChildren().addAll(iv, cap);

        return addRowWithBubble(box, incoming, messageId);
    }

    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId) {
        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-name");

        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, innerSpacer);
        box.getChildren().add(content);

        return addRowWithBubble(box, incoming, messageId);
    }

    public HBox addTextMessage(String text, boolean incoming) {
        return addTextMessage(text, incoming, (String) null);
    }

    public HBox addImageMessage(Image img, String caption, boolean incoming) {
        return addImageMessage(img, caption, incoming, (String) null);
    }

    public HBox addFileMessage(String filename, String meta, boolean incoming) {
        return addFileMessage(filename, meta, incoming, (String) null);
    }

    public HBox addVoiceMessage(String duration, boolean incoming, String fileId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        row.setUserData(fileId);

        HBox voiceBox = new HBox(10);
        voiceBox.setId(incoming ? "incoming-voice" : "outgoing-voice");
        voiceBox.setAlignment(Pos.CENTER_LEFT);

        Button playBtn = new Button("▶");
        playBtn.getStyleClass().add("audio-btn");

        Slider slider = new Slider();
        slider.setPrefWidth(200);

        Label dur = new Label(duration);
        voiceBox.getChildren().addAll(playBtn, slider, dur);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(voiceBox, spacer);
        else          row.getChildren().addAll(spacer, voiceBox);

        attachSideMenu(row, spacer, incoming, fileId);

        controller.getMessageContainer().getChildren().add(row);
        if (!incoming && fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
        scrollToBottom();
        return row;
    }

    public HBox addVideoMessage(String filename, String meta, boolean incoming, String fileId) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);
        row.setUserData(fileId);

        VBox box = new VBox(6);
        box.setId(incoming ? "incoming-video" : "outgoing-video");
        box.setAlignment(Pos.CENTER_LEFT);

        Region videoArea = new Region();
        videoArea.setPrefSize(320, 180);
        videoArea.setStyle("-fx-background-color: #111111; -fx-background-radius: 8;");

        VBox controls = new VBox(4);
        Button playBtn = new Button("▶");
        Slider slider = new Slider();
        slider.setPrefWidth(220);
        Label nameLbl = new Label(filename);
        nameLbl.getStyleClass().add("file-name");
        Label metaLbl = new Label(meta);
        metaLbl.getStyleClass().add("meta");
        controls.getChildren().addAll(playBtn, slider, nameLbl, metaLbl);

        box.getChildren().addAll(videoArea, controls);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(box, spacer);
        else          row.getChildren().addAll(spacer, box);

        attachSideMenu(row, spacer, incoming, fileId);

        controller.getMessageContainer().getChildren().add(row);
        if (!incoming && fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
        scrollToBottom();
        return row;
    }

    public void applyStatusLabel(Label lbl, boolean online, String lastSeenIso) {
        if (lbl == null) return;
        lbl.getStyleClass().removeAll("chat-status-online", "chat-status-offline");
        if (online) {
            lbl.setText("Online");
            lbl.getStyleClass().add("chat-status-online");
        } else {
            lbl.setText("Offline" + controller.humanize(lastSeenIso, true));
            lbl.getStyleClass().add("chat-status-offline");
        }
    }
}

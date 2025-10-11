package client.controller.mid;

import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.util.Duration;

import client.controller.MidController;

public class UIMessageHandler {
    private final MidController controller;

    public UIMessageHandler(MidController controller) {
        this.controller = controller;
    }

    /* chỉ giữ size nếu meta có cả MIME • SIZE */
    private static String normalizeSizeOnly(String meta) {
        if (meta == null) return "";
        String m = meta;
        int bullet = m.lastIndexOf('•');
        if (bullet >= 0) m = m.substring(bullet + 1);
        m = m.trim();
        if (m.matches("(?i)\\d+(?:\\.\\d+)?\\s*(B|KB|MB|GB|TB)")) return m;
        if (m.matches("(?i)\\d+(?:\\.\\d+)?(B|KB|MB|GB|TB)")) return m;
        return "";
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
        cm.getStyleClass().add("msg-context");
        MenuItem miEdit = new MenuItem("Chỉnh sửa");
        miEdit.getStyleClass().add("msg-context-item");
        MenuItem miDelete = new MenuItem("Xóa");
        miDelete.getStyleClass().add("msg-context-item-danger");
        cm.getItems().addAll(miEdit, miDelete);

        Node bubble = null;
        if (incoming) {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(0);
        } else {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(row.getChildren().size() - 1);
        }
        String bubbleId = (bubble instanceof Region r) ? r.getId() : null;

        boolean editable = "outgoing-text".equals(bubbleId);
        miEdit.setDisable(!editable);

        final Label labelRef = findTextLabelInRow(row, incoming);
        final boolean canEdit = (labelRef != null) && "outgoing-text".equals(
                (row.getChildren().isEmpty() ? null
                 : ((Region)(incoming ? row.getChildren().get(0) : row.getChildren().get(row.getChildren().size()-1)))).getId());
        miEdit.setDisable(!canEdit);
        miEdit.setOnAction(e -> {
            if (!canEdit) return;
            final Object msgId = (messageId != null) ? messageId : row.getUserData();
            if (msgId == null) return;
            final String current = labelRef.getText();

            TextInputDialog dialog = new TextInputDialog(current);
            dialog.setTitle("Chỉnh sửa tin nhắn");
            dialog.setHeaderText(null);
            dialog.setContentText("Nội dung mới:");
            dialog.getDialogPane().getStyleClass().add("msg-edit-dialog");

            dialog.showAndWait().ifPresent(newText -> {
                String trimmed = (newText == null) ? "" : newText.trim();
                if (trimmed.isEmpty() || trimmed.equals(current)) return;

                labelRef.setText(trimmed);
                try {
                    long id = Long.parseLong(String.valueOf(msgId));
                    if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                        controller.getConnection().editMessage(
                            id,
                            controller.getCurrentUser().getUsername(),
                            controller.getSelectedUser().getUsername(),
                            trimmed
                        );
                    }
                } catch (Exception ex) {
                    System.err.println("[EDIT] send failed: " + ex.getMessage());
                }
            });
        });

        miDelete.setOnAction(e -> {
            controller.getMessageContainer().getChildren().remove(row);
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

        if (incoming) spacerBox.getChildren().addAll(holder, filler);
        else          spacerBox.getChildren().addAll(filler, holder);

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

    /* IMAGE: chỉ ImageView, không label */
    public HBox addImageMessage(Image img, String caption, boolean incoming, String messageId) {
        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        box.getChildren().add(iv);
        return addRowWithBubble(box, incoming, messageId);
    }

    /* FILE: chỉ tên + kích thước (lọc MIME) */
    public HBox addFileMessage(String filename, String meta, boolean incoming, String messageId) {
        VBox box = new VBox();
        box.setId(incoming ? "incoming-file" : "outgoing-file");
        box.setPadding(new Insets(8, 12, 8, 12));

        HBox content = new HBox(10);
        content.setAlignment(Pos.CENTER_LEFT);

        Label icon = new Label("📄");
        icon.setStyle("-fx-font-size:20px;");

        Label nameLbl = new Label(filename == null ? "" : filename);
        nameLbl.setId("fileNamePrimary");
        nameLbl.getStyleClass().add("file-name");

        // --- LẤY CHỈ SIZE ---
        String sizeOnly = normalizeSizeOnly(meta);

        // Nếu meta trống, thử lấy size từ dlPath theo fileId (messageId)
        if ((sizeOnly == null || sizeOnly.isBlank()) && messageId != null) {
            var f = controller.getDlPath().get(messageId);
            if (f != null && f.exists()) {
                sizeOnly = UtilHandler.humanBytes(f.length());
            }
        }

        Label metaLbl = new Label(sizeOnly == null ? "" : sizeOnly);
        metaLbl.setId("fileMeta");
        metaLbl.getStyleClass().add("meta");

        VBox info = new VBox(2);
        info.setId("fileInfoBox");
        info.getChildren().addAll(nameLbl, metaLbl);

        Region innerSpacer = new Region();
        HBox.setHgrow(innerSpacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, innerSpacer);
        box.getChildren().add(content);

        // Tạo row & gán userData = messageId để về sau cập nhật size
        HBox row = addRowWithBubble(box, incoming, messageId);

        // Nếu vẫn chưa có size (file chưa có trên đĩa), cập nhật hậu kỳ khi có dữ liệu
        if ((sizeOnly == null || sizeOnly.isBlank()) && messageId != null && controller.getMediaHandler() != null) {
            Platform.runLater(() -> controller.getMediaHandler().updateGenericFileMetaByFid(messageId));
        }

        return row;
    }


    public HBox addTextMessage(String text, boolean incoming) { return addTextMessage(text, incoming, (String) null); }
    public HBox addImageMessage(Image img, String caption, boolean incoming) { return addImageMessage(img, caption, incoming, (String) null); }
    public HBox addFileMessage(String filename, String meta, boolean incoming) { return addFileMessage(filename, meta, incoming, (String) null); }

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
        playBtn.setId("voicePlay");

        Slider slider = new Slider();
        slider.setPrefWidth(200);
        slider.setId("voiceSlider");

        Label dur = new Label(duration);
        dur.setId("voiceDuration");

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
        return row;
    }

    /* VIDEO: chỉ khu vực phát + nút Play + Slider, KHÔNG label */
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
        videoArea.setId("videoArea");

        VBox controls = new VBox(4);
        controls.setId("videoControls");

        Button playBtn = new Button("▶");
        playBtn.setId("videoPlay");

        Slider slider = new Slider();
        slider.setPrefWidth(220);
        slider.setId("videoSlider");

        controls.getChildren().addAll(playBtn, slider);
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

    private Label findTextLabelInRow(HBox row, boolean incoming) {
        Node bubble = null;
        if (incoming) {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(0);
        } else {
            if (!row.getChildren().isEmpty()) bubble = row.getChildren().get(row.getChildren().size() - 1);
        }
        if (!(bubble instanceof VBox vb)) return null;
        for (Node n : vb.getChildren()) {
            if (n instanceof Label lbl) return lbl;
        }
        return null;
    }

    public HBox addCallLogMessage(String iconText, String title, String subtitle, boolean incoming) {
        VBox box = new VBox(8);
        box.setId(incoming ? "incoming-call" : "outgoing-call");
        box.setMaxWidth(420);

        HBox rowTop = new HBox(10);
        rowTop.getStyleClass().add("call-row");
        Label icon = new Label(iconText == null || iconText.isBlank() ? "🎥" : iconText);
        icon.getStyleClass().add("call-icon");

        VBox texts = new VBox(2);
        Label t1 = new Label(title == null ? "" : title);
        t1.getStyleClass().add("call-title");
        Label t2 = new Label(subtitle == null ? "" : subtitle);
        t2.getStyleClass().add("call-subtitle");
        texts.getChildren().addAll(t1, t2);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);

        rowTop.getChildren().addAll(icon, texts, grow);

        Region sep = new Region();
        sep.getStyleClass().add("call-sep");

        Button redial = new Button("Gọi lại");
        redial.getStyleClass().add("call-redial");
        redial.setOnAction(e -> controller.callCurrentPeer());

        box.getChildren().addAll(rowTop, sep, redial);

        return addRowWithBubble(box, incoming, (String) null);
    }
}

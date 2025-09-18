package client.controller.mid;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.Instant;

import client.controller.MidController;

public class UIMessageHandler {
    private final MidController controller;

    public UIMessageHandler(MidController controller) {
        this.controller = controller;
    }

    public HBox addTextMessage(String text, boolean incoming) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        VBox bubble = new VBox();
        bubble.setMaxWidth(420);
        bubble.setId(incoming ? "incoming-text" : "outgoing-text");

        Label lbl = new Label(text);
        lbl.setWrapText(true);
        bubble.getChildren().add(lbl);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(bubble, spacer);
        else row.getChildren().addAll(spacer, bubble);

        controller.getMessageContainer().getChildren().add(row);
        return row;
    }

    public HBox addImageMessage(Image img, String caption, boolean incoming) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

        VBox box = new VBox(4);
        box.setId(incoming ? "incoming-image" : "outgoing-image");

        ImageView iv = new ImageView(img);
        iv.setFitWidth(260);
        iv.setPreserveRatio(true);

        Label cap = new Label(caption);
        box.getChildren().addAll(iv, cap);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        if (incoming) row.getChildren().addAll(box, spacer);
        else row.getChildren().addAll(spacer, box);

        controller.getMessageContainer().getChildren().add(row);
        return row;
    }

    public HBox addFileMessage(String filename, String meta, boolean incoming) {
        if (controller.getMessageContainer().getChildren().size() > 100) {
            controller.getMessageContainer().getChildren().remove(0);
        }
        HBox row = new HBox(6);
        row.setAlignment(incoming ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT);

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

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        content.getChildren().addAll(icon, info, spacer);
        box.getChildren().add(content);

        if (incoming) row.getChildren().addAll(box, new Region());
        else row.getChildren().addAll(new Region(), box);

        controller.getMessageContainer().getChildren().add(row);
        return row;
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
        else row.getChildren().addAll(spacer, voiceBox);

        controller.getMessageContainer().getChildren().add(row);
        if (!incoming && fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
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
        else row.getChildren().addAll(spacer, box);

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
}
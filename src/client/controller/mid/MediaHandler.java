package client.controller.mid;

import client.controller.MidController;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;

public class MediaHandler {
    private final MidController controller;

    public MediaHandler(MidController controller) {
        this.controller = controller;
    }

    public void updateVoiceBubbleFromUrl(HBox row, String fileUrl) {
        try {
            Media media = new Media(fileUrl);
            MediaPlayer player = new MediaPlayer(media);

            HBox voiceBox = (HBox) row.getChildren().get(row.getAlignment() == Pos.CENTER_LEFT ? 0 : 1);
            Button playBtn = (Button) voiceBox.getChildren().get(0);
            Slider slider = (Slider) voiceBox.getChildren().get(1);
            Label durLbl = (Label) voiceBox.getChildren().get(2);

            playBtn.setText("▶");
            player.setOnError(() -> {
                System.err.println("[AUDIO] Player error: " + player.getError().getMessage());
                Platform.runLater(() -> controller.showErrorAlert("Phát audio lỗi: " + player.getError().getMessage()));
            });
            player.setOnReady(() -> {
                double total = player.getTotalDuration().toSeconds();
                slider.setMax(total > 0 ? total : 0);
                if (total > 0) durLbl.setText(UtilHandler.formatDuration((int) total));
            });

            player.currentTimeProperty().addListener((obs, old, val) -> {
                if (!slider.isValueChanging()) slider.setValue(val.toSeconds());
            });
            slider.valueChangingProperty().addListener((obs, was, ch) -> {
                if (!ch) player.seek(Duration.seconds(slider.getValue()));
            });
            slider.setOnMouseReleased(e -> player.seek(Duration.seconds(slider.getValue())));

            playBtn.setOnAction(event -> {
                switch (player.getStatus()) {
                    case PLAYING -> {
                        player.pause();
                        playBtn.setText("▶");
                    }
                    default -> {
                        player.play();
                        playBtn.setText("⏸");
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("[AUDIO] attach failed: " + e.getMessage());
            Platform.runLater(() -> controller.showErrorAlert("Phát âm thanh thất bại: " + e.getMessage()));
        }
    }

    public void updateVideoBubbleFromUrl(HBox row, String fileUrl) {
        try {
            Media media = new Media(fileUrl);
            MediaPlayer player = new MediaPlayer(media);
            MediaView view = new MediaView(player);
            view.setFitWidth(320);
            view.setFitHeight(180);
            view.setPreserveRatio(true);

            VBox box = (VBox) row.getChildren().get(row.getAlignment() == Pos.CENTER_LEFT ? 0 : 1);
            box.getChildren().set(0, view);

            VBox controls = (VBox) box.getChildren().get(1);
            Button playBtn = (Button) controls.getChildren().get(0);
            Slider slider = (Slider) controls.getChildren().get(1);

            player.setOnError(() -> {
                System.err.println("[VIDEO] Player error: " + player.getError().getMessage());
                Platform.runLater(() -> controller.showErrorAlert("Phát video lỗi: " + player.getError().getMessage()));
            });
            player.setOnReady(() -> {
                double total = player.getTotalDuration().toSeconds();
                slider.setMax(total > 0 ? total : 0);
            });

            player.currentTimeProperty().addListener((obs, old, val) -> {
                if (!slider.isValueChanging()) slider.setValue(val.toSeconds());
            });
            slider.valueChangingProperty().addListener((obs, was, isChanging) -> {
                if (!isChanging) player.seek(Duration.seconds(slider.getValue()));
            });
            slider.setOnMouseReleased(e -> player.seek(Duration.seconds(slider.getValue())));

            playBtn.setOnAction(e -> {
                switch (player.getStatus()) {
                    case PLAYING -> {
                        player.pause();
                        playBtn.setText("▶");
                    }
                    default -> {
                        player.play();
                        playBtn.setText("⏸");
                    }
                }
            });
        } catch (Exception e) {
            System.err.println("[VIDEO] attach failed: " + e.getMessage());
            Platform.runLater(() -> controller.showErrorAlert("Phát video thất bại: " + e.getMessage()));
        }
    }

    public void updateImageBubbleFromUrl(HBox row, String fileUrl) {
        try {
            Image img = new Image(fileUrl, true);
            Platform.runLater(() -> {
                VBox box = (VBox) row.getChildren().get(
                    row.getAlignment() == Pos.CENTER_LEFT ? 0 : 1
                );
                ImageView iv = (ImageView) box.getChildren().get(0);
                iv.setImage(img);
            });
        } catch (Exception e) {
            System.err.println("[IMAGE] updateImageBubbleFromUrl failed: " + e.getMessage());
        }
    }
}
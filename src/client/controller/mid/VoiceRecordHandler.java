package client.controller.mid;

import javax.sound.sampled.*;
import java.io.*;
import java.util.function.Consumer;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.scene.media.AudioClip;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

public class VoiceRecordHandler {
	private Timeline timeline; 

	public void showVoiceRecordDialog(Window owner, AudioFormat format, File audioFile, Consumer<byte[]> onComplete) {
	    Dialog<ButtonType> dialog = new Dialog<>();
	    dialog.setTitle("Ghi âm");
	    dialog.getDialogPane().setPrefSize(320, 280);
	    dialog.getDialogPane().setId("voice-dialog");

	    VBox dialogContent = new VBox(12);
	    dialogContent.setAlignment(Pos.CENTER);
	    dialogContent.setPadding(new Insets(20));

	    Label micIcon = new Label("🎤");
	    micIcon.setId("mic-icon");
	    Timeline micBlink = new Timeline(
	        new KeyFrame(Duration.seconds(0.5), e -> micIcon.setOpacity(0.5)),
	        new KeyFrame(Duration.seconds(1.0), e -> micIcon.setOpacity(1.0))
	    );
	    micBlink.setCycleCount(Timeline.INDEFINITE);
	    micBlink.play();

	    Label statusLabel = new Label("Đang ghi âm...");
	    statusLabel.setId("status-label");

	    Label timerLabel = new Label("00:00 / 00:30");
	    timerLabel.setId("timer-label");

	    HBox waveBox = new HBox(4);
	    waveBox.setAlignment(Pos.CENTER);
	    Rectangle[] waves = new Rectangle[5];
	    for (int i = 0; i < waves.length; i++) {
	        waves[i] = new Rectangle(6, 20);
	        waves[i].setId("wave-bar-" + i);
	        waves[i].setArcWidth(4);
	        waves[i].setArcHeight(4);
	    }
	    waveBox.getChildren().addAll(waves);
	    Timeline waveAnimation = new Timeline();
	    waveAnimation.setCycleCount(Timeline.INDEFINITE);
	    waveAnimation.play();

	    HBox buttonBox = new HBox(12);
	    buttonBox.setAlignment(Pos.CENTER);
	    Button stopButton = new Button("Dừng");
	    stopButton.setId("stop-button");
	    Button cancelButton = new Button("Hủy");
	    cancelButton.setId("cancel-button");
	    buttonBox.getChildren().addAll(stopButton, cancelButton);

	    dialogContent.getChildren().addAll(micIcon, statusLabel, timerLabel, waveBox, buttonBox);
	    dialog.getDialogPane().setContent(dialogContent);
	    dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
	    dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setVisible(false);

	    final boolean[] isRecording = {true};
	    final byte[][] audioBytesRef = {null};
	    final int maxDurationSec = 30;
	    final int[] secondsElapsed = {0};
	    
	    // Khởi tạo timeline
	    timeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
	        secondsElapsed[0]++;
	        int remaining = maxDurationSec - secondsElapsed[0];
	        timerLabel.setText(String.format("%02d:%02d / %02d:%02d",
	            secondsElapsed[0] / 60, secondsElapsed[0] % 60, maxDurationSec / 60, maxDurationSec % 60));
	        if (secondsElapsed[0] >= maxDurationSec) {
	            isRecording[0] = false;
	            timeline.stop();
	            micBlink.stop();
	            waveAnimation.stop();
	            switchToPlaybackMode(dialog, statusLabel, timerLabel, buttonBox, micBlink, waveAnimation, audioBytesRef, format, audioFile, onComplete);
	        }
	    }));
	    timeline.setCycleCount(maxDurationSec);
	    timeline.play();

	    playBeepSound("/sounds/beep-start.wav");

	    Stage dialogStage = (Stage) dialog.getDialogPane().getScene().getWindow();
	    dialogStage.setAlwaysOnTop(true);
	    dialogStage.initOwner(owner);

	    Thread recordThread = new Thread(() -> {
	        try {
	            if (!AudioSystem.isLineSupported(new DataLine.Info(TargetDataLine.class, format))) {
	                Platform.runLater(() -> {
	                    dialog.close();
	                    micBlink.stop();
	                    waveAnimation.stop();
	                    showErrorAlert("Hệ thống không hỗ trợ ghi âm định dạng này.");
	                    onComplete.accept(null);
	                });
	                return;
	            }

	            TargetDataLine line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, format));
	            line.open(format);
	            line.start();

	            ByteArrayOutputStream out = new ByteArrayOutputStream();
	            int bufferSize = (int) format.getSampleRate() * format.getFrameSize();
	            byte[] buffer = new byte[bufferSize];
	            long startTime = System.currentTimeMillis();

	            while (isRecording[0] && System.currentTimeMillis() - startTime < maxDurationSec * 1000) {
	                int count = line.read(buffer, 0, buffer.length);
	                if (count > 0) {
	                    out.write(buffer, 0, count);
	                    updateWaveFromAudio(waves, buffer, count);
	                }
	            }

	            line.stop();
	            line.close();
	            audioBytesRef[0] = out.toByteArray();
	            out.close();

	            Platform.runLater(() -> {
	                playBeepSound("/sounds/beep-end.wav");
	                switchToPlaybackMode(dialog, statusLabel, timerLabel, buttonBox, micBlink, waveAnimation, audioBytesRef, format, audioFile, onComplete);
	            });

	        } catch (Exception e) {
	            Platform.runLater(() -> {
	                dialog.close();
	                micBlink.stop();
	                waveAnimation.stop();
	                showErrorAlert("Lỗi ghi âm: " + e.getMessage());
	                onComplete.accept(null);
	            });
	            System.out.println("[VOICE] Lỗi ghi âm: " + e.getMessage());
	            e.printStackTrace(System.out);
	            if (audioFile.exists()) {
	                audioFile.delete();
	                System.out.println("[VOICE] Đã xóa file tạm do lỗi ghi âm: " + audioFile.getAbsolutePath());
	            }
	        }
	    }, "record-voice");
	    recordThread.setDaemon(true);
	    recordThread.start();

	    stopButton.setOnAction(e -> {
	        isRecording[0] = false;
	        timeline.stop();
	        micBlink.stop();
	        waveAnimation.stop();
	        playBeepSound("/sounds/beep-end.wav");
	        switchToPlaybackMode(dialog, statusLabel, timerLabel, buttonBox, micBlink, waveAnimation, audioBytesRef, format, audioFile, onComplete);
	    });

	    cancelButton.setOnAction(e -> {
	        isRecording[0] = false;
	        timeline.stop();
	        micBlink.stop();
	        waveAnimation.stop();
	        dialog.close();
	        if (audioFile.exists()) {
	            audioFile.delete();
	            System.out.println("[VOICE] Đã xóa file tạm do hủy: " + audioFile.getAbsolutePath());
	        }
	        onComplete.accept(null);
	    });

	    dialog.show();
	}

    private void switchToPlaybackMode(Dialog<ButtonType> dialog, Label statusLabel, Label timerLabel, HBox buttonBox,
                                      Timeline micBlink, Timeline waveAnimation, final byte[][] audioBytesRef,
                                      AudioFormat format, File audioFile, Consumer<byte[]> onComplete) {
        statusLabel.setText("Phát lại để kiểm tra");
        timerLabel.setText("Nhấn 'Phát' để nghe");
        buttonBox.getChildren().clear();

        Button playButton = new Button("Phát");
        playButton.setId("play-button");
        Button sendButton = new Button("Gửi");
        sendButton.setId("send-button");
        Button cancelPlaybackButton = new Button("Hủy");
        cancelPlaybackButton.setId("cancel-button");
        buttonBox.getChildren().addAll(playButton, sendButton, cancelPlaybackButton);

        playButton.setOnAction(e -> {
            if (audioBytesRef[0] != null) {
                try {
                    File tempPlaybackFile = new File("temp", "playback-" + System.currentTimeMillis() + ".wav");
                    try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(audioBytesRef[0]), format, audioBytesRef[0].length / format.getFrameSize())) {
                        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, tempPlaybackFile);
                    }
                    AudioClip clip = new AudioClip(tempPlaybackFile.toURI().toString());
                    clip.play();
                    tempPlaybackFile.deleteOnExit();
                } catch (Exception ex) {
                    showErrorAlert("Lỗi phát lại: " + ex.getMessage());
                }
            }
        });

        sendButton.setOnAction(e -> {
            dialog.close();
            onComplete.accept(audioBytesRef[0]);
        });

        cancelPlaybackButton.setOnAction(e -> {
            dialog.close();
            if (audioFile.exists()) {
                audioFile.delete();
                System.out.println("[VOICE] Đã xóa file tạm do hủy phát lại: " + audioFile.getAbsolutePath());
            }
            onComplete.accept(null);
        });
    }

    private void updateWaveFromAudio(Rectangle[] waves, byte[] buffer, int count) {
        if (count == 0) return;
        double rms = 0;
        for (int i = 0; i < count; i += 2) {
            int sample = (buffer[i + 1] << 8) | (buffer[i] & 0xFF);
            rms += sample * sample;
        }
        rms = Math.sqrt(rms / (count / 2));
        double level = Math.min(rms / 32768.0, 1.0);
        Platform.runLater(() -> {
            for (int i = 0; i < waves.length; i++) {
                waves[i].setHeight(10 + level * 30 + Math.random() * 10);
            }
        });
    }

    private void playBeepSound(String soundPath) {
        try {
            InputStream is = getClass().getResourceAsStream(soundPath);
            if (is != null) {
                AudioClip beep = new AudioClip(new ByteArrayInputStream(is.readAllBytes()).toString());
                beep.play();
            }
        } catch (Exception e) {
            System.out.println("[VOICE] Không thể phát âm báo: " + e.getMessage());
        }
    }

    private void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
package client.controller;

import client.signaling.CallSignalingService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class VideoCallController {

    public enum Mode { INCOMING, OUTGOING, CONNECTING, CONNECTED }

    @FXML private Label statusLabel;
    @FXML private Label peerLabel;
    @FXML private Label avatarLabel;

    @FXML private HBox incomingBtns, outgoingBtns, connectedBtns;

    @FXML private StackPane videoStack;      
    @FXML private ImageView remoteVideo;    
    @FXML private ImageView localPreview;   

    @FXML private Button acceptBtn, rejectBtn, cancelBtn, hangupBtn, videoToggleBtn, audioToggleBtn;
    @FXML private Label cameraOffLabel; 

    private CallSignalingService callSvc;
    private String peer;
    private String callId;
    private Mode mode = Mode.INCOMING;
    private Runnable onCloseWindow; 
    
    private boolean isVideoEnabled = true; 
    private boolean isAudioEnabled = true; 
    private MidController controller;

    public void init(CallSignalingService svc, String peer, String callId,
                     Mode initialMode, Runnable onCloseWindow, MidController controller) {
        this.callSvc = svc;
        this.peer = peer;
        this.callId = callId;
        this.onCloseWindow = onCloseWindow;
        
        this.controller = controller;
        
        updateVideoButtonText();
        updateAudioButtonText();
        
        videoToggleBtn.setOnAction(e -> toggleVideo());
        audioToggleBtn.setOnAction(e -> toggleAudio());

        peerLabel.setText("@" + peer);
        String initials = (peer == null || peer.isEmpty())
                ? "?" : String.valueOf(Character.toUpperCase(peer.charAt(0)));
        avatarLabel.setText(initials);

        // Bind kích thước khung lớn theo container
        remoteVideo.setPreserveRatio(true);
        remoteVideo.setSmooth(true);
        remoteVideo.fitWidthProperty().bind(videoStack.widthProperty());
        remoteVideo.fitHeightProperty().bind(videoStack.heightProperty());

        // PIP cố định
        localPreview.setPreserveRatio(true);
        localPreview.setSmooth(true);
        localPreview.setFitWidth(240);

        setMode(initialMode);
        wireButtons();
    }

    public void setMode(Mode m) {
        this.mode = m;
        String statusText = switch (m) {
            case INCOMING   -> "Incoming call…";
            case OUTGOING   -> "Calling…";
            case CONNECTING -> "Connecting…";
            case CONNECTED  -> "Connected";
        };
        statusLabel.setText(statusText);

        setGroupVisible(incomingBtns,  m == Mode.INCOMING);
        setGroupVisible(outgoingBtns,  m == Mode.OUTGOING || m == Mode.CONNECTING);
        setGroupVisible(connectedBtns, m == Mode.CONNECTED);

        boolean showVideo = (m == Mode.CONNECTED);
        videoStack.setVisible(showVideo);
        videoStack.setManaged(showVideo);

        Platform.runLater(() -> {
            var scene = statusLabel.getScene();
            if (scene != null && scene.getWindow() instanceof Stage stage) {
                if (showVideo) {
                    stage.setResizable(true);
                    stage.setMinWidth(940);
                    stage.setMinHeight(640);
                } else {
                    stage.setResizable(false);
                    stage.setWidth(480);
                    stage.setHeight(260);
                }
                stage.sizeToScene();
            }
        });
    }

    private void setGroupVisible(HBox box, boolean visible) {
        box.setVisible(visible);
        box.setManaged(visible);
    }

    private void wireButtons() {
        if (acceptBtn != null) acceptBtn.setOnAction(e -> {
            callSvc.sendAccept(peer, callId);
            setMode(Mode.CONNECTING);
        });
        if (rejectBtn != null) rejectBtn.setOnAction(e -> { callSvc.sendReject(peer, callId); safeClose(); });
        if (cancelBtn != null) cancelBtn.setOnAction(e -> { callSvc.sendCancel(peer, callId); safeClose(); });
        if (hangupBtn != null) hangupBtn.setOnAction(e -> { callSvc.sendEnd(peer, callId); safeClose(); });
    }

    /** MidController sẽ đóng Stage thật khi END. */
    public void safeClose() {
        if (onCloseWindow != null) Platform.runLater(onCloseWindow);
    }

    // === Getters để LanVideoSession set hình ===
    public ImageView getRemoteView() { return remoteVideo; }
    public ImageView getLocalView()  { return localPreview; }
    
    private void toggleVideo() {
        if (controller.getVideoSession() != null) {
            isVideoEnabled = !isVideoEnabled;
            controller.getVideoSession().setVideoEnabled(isVideoEnabled);
            updateVideoButtonText();
            cameraOffLabel.setVisible(!isVideoEnabled);
            localPreview.setVisible(isVideoEnabled);
        }
    }

    private void toggleAudio() {
        if (controller.getAudioSession() != null) {
            isAudioEnabled = !isAudioEnabled;
            controller.getAudioSession().setAudioEnabled(isAudioEnabled);
            updateAudioButtonText();
        }
    }

    private void updateVideoButtonText() {
        videoToggleBtn.setText(isVideoEnabled ? "📷" : "📷 Tắt");
    }

    private void updateAudioButtonText() {
        audioToggleBtn.setText(isAudioEnabled ? "🎤" : "🎤 Tắt");
    }
}

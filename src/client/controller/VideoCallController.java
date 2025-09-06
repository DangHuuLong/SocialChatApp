package client.controller;

import client.signaling.CallSignalingService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/** Cửa sổ gọi gọn – chưa gắn media (GĐ4). */
public class VideoCallController {

    public enum Mode { INCOMING, OUTGOING, CONNECTING, CONNECTED }

    @FXML private Label statusLabel;
    @FXML private Label peerLabel;
    @FXML private Label avatarLabel;

    @FXML private HBox incomingBtns, outgoingBtns, connectedBtns;
    @FXML private StackPane videoBox; // ẩn khi chưa CONNECTED

    @FXML private Button acceptBtn, rejectBtn, cancelBtn, hangupBtn;

    private CallSignalingService callSvc;
    private String peer;
    private String callId;
    private Mode mode = Mode.INCOMING;
    private Runnable onCloseWindow; // MidController truyền vào để đóng cửa sổ khi END

    public void init(CallSignalingService svc, String peer, String callId,
                     Mode initialMode, Runnable onCloseWindow) {
        this.callSvc = svc;
        this.peer = peer;
        this.callId = callId;
        this.onCloseWindow = onCloseWindow;

        peerLabel.setText("@" + peer);
        String initials = peer == null || peer.isEmpty()
                ? "?" : ("" + Character.toUpperCase(peer.charAt(0)));
        avatarLabel.setText(initials);

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
        if (videoBox != null) {
            videoBox.setVisible(showVideo);
            videoBox.setManaged(showVideo);
        }

        // co/giãn cửa sổ theo mode
        Platform.runLater(() -> {
            var scene = statusLabel.getScene();
            if (scene != null && scene.getWindow() instanceof javafx.stage.Stage stage) {
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
        if (acceptBtn != null) acceptBtn.setOnAction(e -> callSvc.sendAccept(peer, callId));
        if (rejectBtn != null) rejectBtn.setOnAction(e -> { callSvc.sendReject(peer, callId); safeClose(); });
        if (cancelBtn != null) cancelBtn.setOnAction(e -> { callSvc.sendCancel(peer, callId); safeClose(); });
        if (hangupBtn != null) hangupBtn.setOnAction(e -> { callSvc.sendEnd(peer, callId); safeClose(); });
    }

    /** MidController sẽ thực hiện đóng Stage thật. */
    public void safeClose() {
        if (onCloseWindow != null) Platform.runLater(onCloseWindow);
    }
}

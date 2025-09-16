package client.controller;

import client.ClientConnection;
import client.media.CallOffer;
import client.media.LanAudioSession;
import client.media.LanVideoSession;
import client.media.LanVideoSession.OfferInfo;
import client.signaling.CallSignalListener;
import client.signaling.CallSignalingService;
import common.Frame;
import common.MessageType;
import common.User;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.Stage;
import javafx.util.Duration;
import server.dao.UserDAO;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MidController implements CallSignalListener {
    private Label currentChatName;
    private Label currentChatStatus;
    private VBox messageContainer;
    private TextField messageField;
    private Button logoutBtn;

    private RightController rightController;
    private CallSignalingService callSvc;
    private Stage callStage;
    private VideoCallController callCtrl;
    private String currentCallId;
    private String currentPeer;
    private boolean isCaller = false;

    private User currentUser;
    private User selectedUser;
    private ClientConnection connection;
    private User currentPeerUser;

    private LanVideoSession videoSession;
    private LanAudioSession audioSession;

    // ========= File/UI state =========
    private final Map<String, List<Frame>> pendingFileEvents = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToName = new ConcurrentHashMap<>();
    private final Map<String, String> fileIdToMime = new ConcurrentHashMap<>();
    public  final Map<String, HBox>   outgoingFileBubbles = new ConcurrentHashMap<>();

    // Bên nhận: ghi file tạm
    private final Map<String, BufferedOutputStream> dlOut = new ConcurrentHashMap<>();
    private final Map<String, File> dlPath = new ConcurrentHashMap<>();

    public void bind(Label currentChatName, Label currentChatStatus,
                     VBox messageContainer, TextField messageField, Button logoutBtn) {
        this.currentChatName = currentChatName;
        this.currentChatStatus = currentChatStatus;
        this.messageContainer = messageContainer;
        this.messageField = messageField;
        this.logoutBtn = logoutBtn;

        if (this.messageField != null) this.messageField.setOnAction(e -> onSendMessage());
        if (this.logoutBtn != null) this.logoutBtn.setOnAction(e -> onLogout());
    }

    public void setRightController(RightController rc) { this.rightController = rc; }
    public void setCurrentUser(User user) { this.currentUser = user; }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        if (this.connection != null) {
            this.connection.setMidController(this);
            this.connection.startListener(
                f -> Platform.runLater(() -> handleServerFrame(f)),
                err -> System.err.println("[NET] Disconnected: " + err)
            );
        }
    }

    public void openConversation(User u) {
        System.out.println("[OPEN] conversation with " + u.getUsername());
        this.selectedUser = u;
        if (currentChatName != null) currentChatName.setText(u.getUsername());

        try {
            UserDAO.Presence p = UserDAO.getPresence(u.getId());
            boolean online = p != null && p.online;
            String lastSeen = (p != null) ? p.lastSeenIso : null;

            applyStatusLabel(currentChatStatus, online, lastSeen);
            if (rightController != null) rightController.showUser(u, online, lastSeen);
        } catch (SQLException e) {
            e.printStackTrace();
            applyStatusLabel(currentChatStatus, false, null);
            if (rightController != null) rightController.showUser(u, false, null);
        }

        if (messageContainer != null) {
            if (messageContainer.getChildren().size() > 100) {
                messageContainer.getChildren().remove(0, messageContainer.getChildren().size() - 100);
            }
            messageContainer.getChildren().clear();
        }
        if (connection != null && connection.isAlive()) {
            try {
                connection.history(currentUser.getUsername(), u.getUsername(), 50);
            } catch (Exception e) {
                System.err.println("[HISTORY] Failed to load history: " + e.getMessage());
                Platform.runLater(() -> showErrorAlert("Lịch sử tin nhắn không tải được: " + e.getMessage()));
            }
        }

        this.currentPeerUser = u;
        // xử lý FILE_EVT pending
        List<Frame> pending = pendingFileEvents.remove(u.getUsername());
        if (pending != null) pending.forEach(this::handleServerFrame);
    }

    private void handleServerFrame(Frame f) {
        if (f == null) return;
        String openPeer = (selectedUser != null) ? selectedUser.getUsername() : null;

        switch (f.type) {
            case DM -> {
                String sender = f.sender;
                String body = f.body;
                if (openPeer != null && openPeer.equals(sender)) {
                    addTextMessage(body, true);
                }
            }
            case HISTORY -> {
                String line = f.body == null ? "" : f.body.trim();
                if (line.startsWith("[HIST IN]")) {
                    String payload = line.substring(9).trim();
                    int p = payload.indexOf(": ");
                    if (p > 0) {
                        String sender = payload.substring(0, p);
                        String body = payload.substring(p + 2);
                        if (openPeer != null && openPeer.equals(sender)) {
                            addTextMessage(body, true);
                        }
                    }
                } else if (line.startsWith("[HIST OUT]")) {
                    String body = line.substring(10).trim();
                    addTextMessage(body, false);
                }
            }
            case FILE_EVT, AUDIO_EVT -> { // Bên nhận: được báo có file
                String json = f.body == null ? "" : f.body;
                String from = jsonGet(json, "from");
                String name = jsonGet(json, "name");
                String mime = jsonGet(json, "mime");
                String fileId = jsonGet(json, "id");
                long bytes = parseLongSafe(jsonGet(json, "bytes"), 0);
                int duration = parseIntSafe(jsonGet(json, "duration"), 0);

                fileIdToName.put(fileId, name);
                if (mime != null && !mime.isBlank()) fileIdToMime.put(fileId, mime);

                if (openPeer != null && openPeer.equals(from)) {
                    MediaKind kind = classifyMedia(mime, name);
                    String meta = (mime == null ? "" : mime) + " • " + humanBytes(bytes);
                    HBox row;
                    switch (kind) {
		                case IMAGE -> {
		                    Image img = new WritableImage(8, 8);
		                    row = addImageMessage(img, name + " • " + humanBytes(bytes), true);
		                    row.setUserData(fileId);
		                }
                        case AUDIO -> row = addVoiceMessage(formatDuration(duration), true, fileId);
                        case VIDEO -> row = addVideoMessage(name, meta, true, fileId);
                        default     -> {
                            row = addFileMessage(name, meta, true);
                            row.setUserData(fileId);
                        }
                    }
                    // Yêu cầu server gửi dữ liệu file về
                    if (connection != null && connection.isAlive()) {
                        try { connection.downloadFile(fileId); }
                        catch (IOException e) { System.err.println("[DL] request failed: " + e.getMessage()); }
                    }
                } else {
                    pendingFileEvents.computeIfAbsent(from, k -> new ArrayList<>()).add(f);
                    System.out.println("[DEBUG] FILE_EVT queued for from=" + from);
                }
            }
            case FILE_META -> { // bên nhận: chuẩn bị file tạm để ghi
                String body = f.body == null ? "" : f.body;
                String mime = jsonGet(body, "mime");
                String fid  = jsonGet(body, "fileId");
                if (fid == null) break;
                if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                fileIdToMime.put(fid, mime);
                try {
                    String ext = guessExt(mime, fileIdToName.get(fid));
                    File tmp = File.createTempFile("im_", "_" + fid + ext);
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp));
                    dlPath.put(fid, tmp);
                    dlOut.put(fid, bos);
                    System.out.println("[DL] open temp " + tmp.getAbsolutePath());
                } catch (Exception ex) {
                    System.err.println("[DL] open failed: " + ex.getMessage());
                }
            }
            case FILE_CHUNK -> { // bên nhận: ghi chunk vào file tạm; chunk cuối thì gắn player
                String fid = f.transferId;
                byte[] data = (f.bin == null) ? new byte[0] : f.bin;
                BufferedOutputStream bos = dlOut.get(fid);
                if (bos != null) {
                    try { if (data.length > 0) bos.write(data); }
                    catch (IOException e) { System.err.println("[DL] write failed: " + e.getMessage()); }
                    if (f.last) {
                        try { bos.flush(); bos.close(); } catch (Exception ignore) {}
                        dlOut.remove(fid);
                        File file = dlPath.remove(fid);
                        if (file != null) {
                            String fileUrl = file.toURI().toString(); // file:///...
                            Platform.runLater(() -> {
                                HBox row = findRowByUserData(fid);
                                if (row == null) return;
                                String mime = fileIdToMime.getOrDefault(fid, "application/octet-stream");
                                MediaKind kind = classifyMedia(mime, fileIdToName.get(fid));
                                try {
                                    switch (kind) {
                                        case AUDIO -> updateVoiceBubbleFromUrl(row, fileUrl);
                                        case VIDEO -> updateVideoBubbleFromUrl(row, fileUrl);
                                        case IMAGE -> updateImageBubbleFromUrl(row, fileUrl);
                                        default -> { /* file thường: có thể thêm nút mở */ }
                                    }
                                } catch (Exception ex) {
                                    System.err.println("[UI] attach player failed: " + ex.getMessage());
                                }
                            });
                        }
                    }
                }
            }
            case ACK -> { // người gửi: sau ACK, gắn player vào bubble bằng file local (ClientConnection sẽ gọi showOutgoingFile trước đó)
                if (f.transferId != null) {
                    // không làm gì ở đây; ClientConnection sau ACK đã gọi update*FromUrl qua Platform.runLater rồi
                }
            }
            case ERROR -> Platform.runLater(() -> showErrorAlert("Lỗi: " + f.body));
        }
    }

    private HBox findRowByUserData(String fid) {
        for (Node n : messageContainer.getChildren()) {
            if (n instanceof HBox h && fid.equals(h.getUserData())) return h;
        }
        return null;
    }

    /* ============ UI helpers ============ */

    private void showErrorAlert(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("Lỗi");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public void onSendMessage() {
        if (messageField == null) return;
        String text = messageField.getText().trim();
        if (text.isEmpty() || selectedUser == null) return;

        if (connection != null && connection.isAlive()) {
            try {
                String from = (currentUser != null ? currentUser.getUsername() : "");
                connection.dm(from, selectedUser.getUsername(), text);
            } catch (IOException ioe) {
                System.err.println("[NET] DM failed: " + ioe.getMessage());
                Platform.runLater(() -> showErrorAlert("Gửi tin nhắn thất bại: " + ioe.getMessage()));
            }
        }
        addTextMessage(text, false);
        messageField.clear();
    }

    public HBox addTextMessage(String text, boolean incoming) {
        if (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
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

        messageContainer.getChildren().add(row);
        return row;
    }

    public HBox addImageMessage(Image img, String caption, boolean incoming) {
        if (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
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

        messageContainer.getChildren().add(row);
        return row;
    }

    public HBox addFileMessage(String filename, String meta, boolean incoming) {
        if (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
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

        messageContainer.getChildren().add(row);
        return row;
    }

    public HBox addVoiceMessage(String duration, boolean incoming, String fileId) {
        if (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
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

        messageContainer.getChildren().add(row);
        if (!incoming && fileId != null) {
            outgoingFileBubbles.put(fileId, row);
        }
        return row;
    }

    public HBox addVideoMessage(String filename, String meta, boolean incoming, String fileId) {
        if (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
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

        messageContainer.getChildren().add(row);
        if (!incoming && fileId != null) {
            outgoingFileBubbles.put(fileId, row);
        }
        return row;
    }
    
    public void showOutgoingFile(String filename, String mime, long bytes, String fileId, String duration) {
        MediaKind kind = classifyMedia(mime, filename);
        String meta = (mime == null ? "" : mime) + " • " + humanBytes(bytes);

        HBox row;
        switch (kind) {
            case IMAGE -> {
                Image img;
                try {
                    // Ưu tiên load ảnh thật từ file local (nếu có)
                    File f = new File("uploads", fileId);
                    if (f.exists()) {
                        img = new Image(f.toURI().toString(), 200, 0, true, true);
                    } else {
                        // fallback placeholder nếu chưa có file
                        img = new WritableImage(8, 8);
                    }
                } catch (Exception e) {
                    img = new WritableImage(8, 8);
                }
                row = addImageMessage(img, filename + " • " + humanBytes(bytes), false);
                row.setUserData(fileId);
            }
            case AUDIO -> {
                row = addVoiceMessage(duration != null ? duration : "--:--", false, fileId);
                row.setUserData(fileId);
            }
            case VIDEO -> {
                row = addVideoMessage(filename, meta, false, fileId);
                row.setUserData(fileId);
            }
            default -> {
                row = addFileMessage(filename, meta, false);
                row.setUserData(fileId);
            }
        }

        if (fileId != null) {
            outgoingFileBubbles.put(fileId, row);
        }
    }



    // ==== GẮN PLAYER TỪ file:// URL (đơn giản, ổn định) ====
    public void updateVoiceBubbleFromUrl(HBox row, String fileUrl) {
        try {
            Media media = new Media(fileUrl);
            MediaPlayer player = new MediaPlayer(media);

            HBox voiceBox = (HBox) row.getChildren().get(row.getAlignment() == Pos.CENTER_LEFT ? 0 : 1);
            Button playBtn = (Button) voiceBox.getChildren().get(0);
            Slider slider  = (Slider)  voiceBox.getChildren().get(1);
            Label  durLbl  = (Label)   voiceBox.getChildren().get(2);

            playBtn.setText("▶");
            player.setOnError(() -> {
                System.err.println("[AUDIO] Player error: " + player.getError().getMessage());
                Platform.runLater(() -> showErrorAlert("Phát audio lỗi: " + player.getError().getMessage()));
            });
            player.setOnReady(() -> {
                double total = player.getTotalDuration().toSeconds();
                slider.setMax(total > 0 ? total : 0);
                if (total > 0) durLbl.setText(formatDuration((int) total));
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
                    case PLAYING -> { player.pause(); playBtn.setText("▶"); }
                    default       -> { player.play();  playBtn.setText("⏸"); }
                }
            });
        } catch (Exception e) {
            System.err.println("[AUDIO] attach failed: " + e.getMessage());
            Platform.runLater(() -> showErrorAlert("Phát âm thanh thất bại: " + e.getMessage()));
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
            Slider slider  = (Slider)  controls.getChildren().get(1);

            player.setOnError(() -> {
                System.err.println("[VIDEO] Player error: " + player.getError().getMessage());
                Platform.runLater(() -> showErrorAlert("Phát video lỗi: " + player.getError().getMessage()));
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
                    case PLAYING -> { player.pause(); playBtn.setText("▶"); }
                    default ->     { player.play();  playBtn.setText("⏸"); }
                }
            });
        } catch (Exception e) {
            System.err.println("[VIDEO] attach failed: " + e.getMessage());
            Platform.runLater(() -> showErrorAlert("Phát video thất bại: " + e.getMessage()));
        }
    }

    public void updateImageBubbleFromUrl(HBox row, String fileUrl) {
        try {
            Image img = new Image(fileUrl, true); // load async từ file://
            Platform.runLater(() -> {
                VBox box = (VBox) row.getChildren().get(
                    row.getAlignment() == Pos.CENTER_LEFT ? 0 : 1
                );
                ImageView iv = (ImageView) box.getChildren().get(0); // phần tử đầu là ImageView
                iv.setImage(img);
            });
        } catch (Exception e) {
            System.err.println("[IMAGE] updateImageBubbleFromUrl failed: " + e.getMessage());
        }
    }

    /* ====== Misc ====== */

    private static long parseLongSafe(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private void onLogout() {
        try { if (currentUser != null) UserDAO.setOnline(currentUser.getId(), false); } catch (SQLException ignored) {}
        if (connection != null) { try { connection.close(); } catch (Exception ignored) {} connection = null; }
        currentUser = null;
        pendingFileEvents.clear();
        fileIdToName.clear();
        fileIdToMime.clear();
        outgoingFileBubbles.clear();

        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (Exception e) { e.printStackTrace(); }
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

    private static String humanBytes(long v) {
        if (v <= 0) return "0 B";
        final String[] u = {"B","KB","MB","GB","TB"};
        int i = 0;
        double d = v;
        while (d >= 1024 && i < u.length - 1) { d /= 1024.0; i++; }
        return (d >= 10 ? String.format("%.0f %s", d, u[i]) : String.format("%.1f %s", d, u[i]));
    }

    private static String formatDuration(int sec) {
        if (sec <= 0) return "--:--";
        int m = sec / 60;
        int s = sec % 60;
        return String.format("%d:%02d", m, s);
    }

    private enum MediaKind { IMAGE, AUDIO, VIDEO, FILE }

    private static MediaKind classifyMedia(String mime, String name) {
        String m = (mime == null ? "" : mime.toLowerCase());
        if (m.startsWith("image/")) return MediaKind.IMAGE;
        if (m.startsWith("audio/")) return MediaKind.AUDIO;
        if (m.startsWith("video/")) return MediaKind.VIDEO;

        String n = (name == null ? "" : name.toLowerCase());
        if (n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp"))
            return MediaKind.IMAGE;
        if (n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".aac"))
            return MediaKind.AUDIO;
        if (n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".mkv") || n.endsWith(".webm"))
            return MediaKind.VIDEO;

        return MediaKind.FILE;
    }

    private static String jsonGet(String json, String key) {
        if (json == null) return null;
        String kq = "\"" + key + "\"";
        int i = json.indexOf(kq);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + kq.length());
        if (colon < 0) return null;
        int j = colon + 1;
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return null;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            if (end < 0) return null;
            return json.substring(j + 1, end);
        } else {
            int end = j;
            while (end < json.length() && "-0123456789".indexOf(json.charAt(end)) >= 0) end++;
            return json.substring(j, end);
        }
    }

    private static String guessExt(String mime, String fallbackName) {
        if (mime == null) return ".bin";
        mime = mime.toLowerCase(Locale.ROOT);
        if (mime.startsWith("audio/")) {
            if (mime.contains("mpeg")) return ".mp3";
            if (mime.contains("wav"))  return ".wav";
            if (mime.contains("ogg"))  return ".ogg";
            if (mime.contains("aac"))  return ".m4a";
            return ".audio";
        }
        if (mime.startsWith("video/")) {
            if (mime.contains("mp4"))  return ".mp4";
            if (mime.contains("webm")) return ".webm";
            if (mime.contains("matroska")) return ".mkv";
            return ".video";
        }
        if (mime.startsWith("image/")) {
            if (mime.contains("jpeg")) return ".jpg";
            if (mime.contains("png"))  return ".png";
            if (mime.contains("gif"))  return ".gif";
            if (mime.contains("bmp"))  return ".bmp";
            if (mime.contains("webp")) return ".webp";
            return ".img";
        }
        // fallback theo tên
        if (fallbackName != null && fallbackName.contains(".")) {
            return fallbackName.substring(fallbackName.lastIndexOf('.'));
        }
        return ".bin";
    }

    /* ================= CALL area giữ nguyên (không đổi) ================= */
    public void callCurrentPeer() {
        if (currentPeerUser == null) { System.out.println("[CALL] No peer selected"); return; }
        startCallTo(currentPeerUser);
    }
    public void setCallService(CallSignalingService svc) { this.callSvc = svc; }
    public void startCallTo(User peerUser) {
        String peer = peerUser.getUsername();
        String callId = java.util.UUID.randomUUID().toString();
        currentPeer = peer; currentCallId = callId; isCaller = true;
        openCallWindow(peer, callId, VideoCallController.Mode.OUTGOING);
        callSvc.sendInvite(peer, callId);
    }
    @Override public void onInvite(String fromUser, String callId) {
        Platform.runLater(() -> { currentPeer = fromUser; currentCallId = callId; isCaller = false;
            openCallWindow(fromUser, callId, VideoCallController.Mode.INCOMING);});
    }
    @Override public void onAccept(String fromUser, String callId) {
        Platform.runLater(() -> {
            if (isCaller && callCtrl != null && fromUser.equals(currentPeer) && callId.equals(currentCallId)) {
                try {
                    videoSession = new LanVideoSession();
                    OfferInfo v = videoSession.prepareCaller();
                    audioSession = new LanAudioSession();
                    int aport = audioSession.prepareCaller(v.host);
                    CallOffer offer = new CallOffer(v.host, v.port, aport);
                    callSvc.sendOffer(currentPeer, currentCallId, offer.toJson());
                    videoSession.startAsCaller(callCtrl.getLocalView(), callCtrl.getRemoteView());
                    audioSession.startAsCaller();
                    callCtrl.setMode(VideoCallController.Mode.CONNECTING);
                } catch (Exception e) {
                    e.printStackTrace(); callSvc.sendEnd(currentPeer, currentCallId); closeCallWindow();
                }
            }
        });
    }
    @Override public void onReject(String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }
    @Override public void onCancel(String fromUser, String callId) { Platform.runLater(this::closeCallWindow); }
    @Override public void onBusy(String fromUser, String callId)   { Platform.runLater(this::closeCallWindow); }
    @Override public void onEnd(String fromUser, String callId)    { Platform.runLater(this::closeCallWindow); }
    @Override public void onOffline(String toUser, String callId)  { }
    @Override public void onOffer(String fromUser, String callId, String sdpJson) {
        Platform.runLater(() -> {
            try {
                if (!fromUser.equals(currentPeer) || !callId.equals(currentCallId)) return;
                CallOffer offer = CallOffer.fromJson(sdpJson);
                videoSession = new LanVideoSession();
                videoSession.startAsCallee(offer.host, offer.vport, callCtrl.getLocalView(), callCtrl.getRemoteView());
                audioSession = new LanAudioSession();
                audioSession.startAsCallee(offer.host, offer.aport);
                callSvc.sendAnswer(currentPeer, currentCallId, "{\"ok\":true}");
                callCtrl.setMode(VideoCallController.Mode.CONNECTED);
            } catch (Exception e) { e.printStackTrace(); callSvc.sendEnd(currentPeer, currentCallId); closeCallWindow(); }
        });
    }
    @Override public void onAnswer(String fromUser, String callId, String sdpJson) {
        Platform.runLater(() -> {
            if (isCaller && callCtrl != null && fromUser.equals(currentPeer) && callId.equals(currentCallId)) {
                callCtrl.setMode(VideoCallController.Mode.CONNECTED);
            }
        });
    }
    @Override public void onIce(String from, String id, String c) { System.out.println("[CALL] ICE len=" + c.length()); }

    private void openCallWindow(String peer, String callId, VideoCallController.Mode mode) {
        try {
            if (callStage != null) { callStage.close(); callStage = null; }
            FXMLLoader fx = new FXMLLoader(getClass().getResource("/client/view/VideoCallOverlay.fxml"));
            Parent root = fx.load();
            callCtrl = fx.getController();
            callCtrl.init(callSvc, peer, callId, mode, this::closeCallWindow);
            callStage = new Stage();
            callStage.setTitle("Call • @" + peer);
            callStage.setResizable(true);
            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            callStage.setScene(scene);
            callStage.setAlwaysOnTop(false);
            callStage.show();
            callStage.requestFocus();
            callStage.setOnCloseRequest(ev -> { ev.consume(); callStage.setIconified(true); });
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void closeCallWindow() {
        if (videoSession != null) { videoSession.stop(); videoSession = null; }
        if (audioSession != null) { audioSession.stop(); audioSession = null; }
        if (callStage != null) { callStage.close(); callStage = null; }
        callCtrl = null; currentCallId = null; currentPeer = null; isCaller = false;
    }
}

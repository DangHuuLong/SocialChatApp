package client.controller.mid;

import common.Frame;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import client.controller.MidController;
import client.controller.mid.UtilHandler.MediaKind;

public class MessageHandler {
    private final MidController controller;
    private static final java.util.Set<String> renderedCallLogIds =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public MessageHandler(MidController controller) {
        this.controller = controller;
    }

    public void handleServerFrame(Frame f) {
        if (f == null) return;
        String openPeer = (controller.getSelectedUser() != null) ? controller.getSelectedUser().getUsername() : null;

        switch (f.type) {
            case DM -> {
                String sender = f.sender;
                String body = f.body == null ? "" : f.body;

                if (body.startsWith("[CALLLOG]")) {
                    if (openPeer != null && openPeer.equals(sender)) {
                        CallLogData d = parseCallLog(body);
                        renderCallLogOnce(d, true);
                    }
                    break;
                }

                if (openPeer != null && openPeer.equals(sender)) {
                    controller.addTextMessage(body, true, f.transferId);
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

                        if (body.startsWith("[CALLLOG]")) {
                            if (openPeer != null && openPeer.equals(sender)) {
                                CallLogData d = parseCallLog(body);
                                renderCallLogOnce(d, true);
                            }
                            break;
                        }

                        if (openPeer != null && openPeer.equals(sender)) {
                            controller.addTextMessage(body, true, f.transferId);
                        }
                    }

                } else if (line.startsWith("[HIST OUT]")) {
                    String body = line.substring(10).trim();

                    if (body.startsWith("[CALLLOG]")) {
                        CallLogData d = parseCallLog(body);
                        renderCallLogOnce(d, false);
                        break;
                    }

                    controller.addTextMessage(body, false, f.transferId);
                }
            }

            case FILE_EVT, AUDIO_EVT -> {
                String json = f.body == null ? "" : f.body;
                String from = UtilHandler.jsonGet(json, "from");
                String name = UtilHandler.jsonGet(json, "name");
                String mime = UtilHandler.jsonGet(json, "mime");
                String fileId = UtilHandler.jsonGet(json, "id");
                long bytes = UtilHandler.parseLongSafe(UtilHandler.jsonGet(json, "bytes"), 0);
                int duration = UtilHandler.parseIntSafe(UtilHandler.jsonGet(json, "duration"), 0);

                controller.getFileIdToName().put(fileId, name);
                if (mime != null && !mime.isBlank()) controller.getFileIdToMime().put(fileId, mime);

                if (openPeer != null && openPeer.equals(from)) {
                    MediaKind kind = UtilHandler.classifyMedia(mime, name);
                    String meta = (mime == null ? "" : mime) + " • " + UtilHandler.humanBytes(bytes);
                    HBox row;
                    switch (kind) {
                        case IMAGE -> {
                            Image img = new WritableImage(8, 8);
                            row = controller.addImageMessage(img, name + " • " + UtilHandler.humanBytes(bytes), true);
                            row.setUserData(fileId);
                        }
                        case AUDIO -> row = controller.addVoiceMessage(UtilHandler.formatDuration(duration), true, fileId);
                        case VIDEO -> row = controller.addVideoMessage(name, meta, true, fileId);
                        default -> {
                            row = controller.addFileMessage(name, meta, true);
                            row.setUserData(fileId);
                        }
                    }
                    if (controller.getConnection() != null && controller.getConnection().isAlive()) {
                        try {
                            controller.getConnection().downloadFile(fileId);
                        } catch (IOException e) {
                            System.err.println("[DL] request failed: " + e.getMessage());
                        }
                    }
                } else {
                    controller.getPendingFileEvents().computeIfAbsent(from, k -> new ArrayList<>()).add(f);
                }
            }

            case FILE_META -> {
                String body = f.body == null ? "" : f.body;
                String mime = UtilHandler.jsonGet(body, "mime");
                String fid = UtilHandler.jsonGet(body, "fileId");
                if (fid == null) break;
                if (mime == null || mime.isBlank()) mime = "application/octet-stream";
                controller.getFileIdToMime().put(fid, mime);
                try {
                    String ext = UtilHandler.guessExt(mime, controller.getFileIdToName().get(fid));
                    File tmp = File.createTempFile("im_", "_" + fid + ext);
                    BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tmp));
                    controller.getDlPath().put(fid, tmp);
                    controller.getDlOut().put(fid, bos);
                } catch (Exception ex) {
                    System.err.println("[DL] open failed: " + ex.getMessage());
                }
            }

            case FILE_CHUNK -> {
                String fid = f.transferId;
                byte[] data = (f.bin == null) ? new byte[0] : f.bin;
                BufferedOutputStream bos = controller.getDlOut().get(fid);
                if (bos != null) {
                    try {
                        if (data.length > 0) bos.write(data);
                    } catch (IOException e) {
                        System.err.println("[DL] write failed: " + e.getMessage());
                    }
                    if (f.last) {
                        try { bos.flush(); bos.close(); } catch (Exception ignore) {}
                        controller.getDlOut().remove(fid);
                        File file = controller.getDlPath().remove(fid);
                        if (file != null) {
                            String fileUrl = file.toURI().toString();
                            Platform.runLater(() -> {
                                HBox row = controller.findRowByUserData(fid);
                                if (row == null) return;
                                String mime = controller.getFileIdToMime().getOrDefault(fid, "application/octet-stream");
                                MediaKind kind = UtilHandler.classifyMedia(mime, controller.getFileIdToName().get(fid));
                                try {
                                    switch (kind) {
                                        case AUDIO -> controller.updateVoiceBubbleFromUrl(row, fileUrl);
                                        case VIDEO -> controller.updateVideoBubbleFromUrl(row, fileUrl);
                                        case IMAGE -> controller.updateImageBubbleFromUrl(row, fileUrl);
                                        default -> {}
                                    }
                                } catch (Exception ex) {
                                    System.err.println("[UI] attach player failed: " + ex.getMessage());
                                }
                            });
                        }
                    }
                }
            }

            case DELETE_MSG -> {
                String id = f.transferId;
                if (id != null) {
                    Platform.runLater(() -> controller.removeMessageById(id));
                }
            }

            case EDIT_MSG -> {
                String id = f.transferId;
                String newBody = (f.body == null) ? "" : f.body;
                if (id != null) {
                    Platform.runLater(() -> controller.updateTextBubbleById(id, newBody));
                }
            }

            case ACK -> {
                if (f.transferId != null && f.body != null &&
                    (f.body.startsWith("OK DM") || f.body.startsWith("OK QUEUED"))) {
                    controller.tagNextPendingOutgoing(f.transferId);
                }
            }

            case ERROR -> Platform.runLater(() -> controller.showErrorAlert("Lỗi: " + f.body));
        }
    }

    public void onSendMessage() {
        if (controller.getMessageField() == null) return;
        String text = controller.getMessageField().getText().trim();
        if (text.isEmpty() || controller.getSelectedUser() == null) return;

        if (controller.getConnection() != null && controller.getConnection().isAlive()) {
            try {
                String from = (controller.getCurrentUser() != null ? controller.getCurrentUser().getUsername() : "");
                controller.getConnection().dm(from, controller.getSelectedUser().getUsername(), text);
            } catch (IOException ioe) {
                System.err.println("[NET] DM failed: " + ioe.getMessage());
                Platform.runLater(() -> controller.showErrorAlert("Gửi tin nhắn thất bại: " + ioe.getMessage()));
            }
        }

        HBox row = controller.addTextMessage(text, false);
        controller.enqueuePendingOutgoing(row);
        controller.getMessageField().clear();
    }

    private static final class CallLogData {
        final String icon, title, subtitle, callId, caller, callee;
        CallLogData(String icon, String title, String subtitle, String callId, String caller, String callee) {
            this.icon = icon; this.title = title; this.subtitle = subtitle;
            this.callId = callId; this.caller = caller; this.callee = callee;
        }
    }

    private static CallLogData parseCallLog(String body) {
        try {
            int i = body.indexOf('{');
            if (!body.startsWith("[CALLLOG]") || i < 0) return null;
            String json = body.substring(i);
            String icon     = UtilHandler.jsonGet(json, "icon");
            String title    = UtilHandler.jsonGet(json, "title");
            String subtitle = UtilHandler.jsonGet(json, "subtitle");
            String callId   = UtilHandler.jsonGet(json, "callId");
            String caller   = UtilHandler.jsonGet(json, "caller");
            String callee   = UtilHandler.jsonGet(json, "callee");
            if (title == null) title = "";
            if (subtitle == null) subtitle = "";
            if (icon == null || icon.isBlank()) icon = "🎥";
            return new CallLogData(icon, title, subtitle, callId, caller, callee);
        } catch (Exception ignore) {
            return null;
        }
    }

    private boolean isIncomingForThisClient(CallLogData d) {
        String me = (controller.getCurrentUser() != null)
                ? controller.getCurrentUser().getUsername() : null;
        if (me == null) return true;
        if (d == null)  return true;

        if (d.caller != null && !d.caller.isBlank()) {
            return !me.equals(d.caller);
        }
        if (d.callee != null && !d.callee.isBlank()) {
            return me.equals(d.callee);
        }
        return true;
    }

    private void renderCallLogOnce(CallLogData d, boolean defaultIncoming) {
        if (d == null) return;
        if (d.callId != null && !d.callId.isBlank()) {
            if (!renderedCallLogIds.add(d.callId)) return;
        }
        boolean incoming = (d.caller != null || d.callee != null)
                ? isIncomingForThisClient(d)
                : defaultIncoming;
        controller.addCallLog(d.icon, d.title, d.subtitle, incoming);
    }
}

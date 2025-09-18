package client.controller.mid;

import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;

import java.io.File;

import client.controller.MidController;
import client.controller.mid.UtilHandler.MediaKind;

public class FileHandler {
    private final MidController controller;

    public FileHandler(MidController controller) {
        this.controller = controller;
    }

    public void showOutgoingFile(String filename, String mime, long bytes, String fileId, String duration) {
        MediaKind kind = UtilHandler.classifyMedia(mime, filename);
        String meta = (mime == null ? "" : mime) + " • " + UtilHandler.humanBytes(bytes);

        HBox row;
        switch (kind) {
            case IMAGE -> {
                Image img;
                try {
                    File f = new File("Uploads", fileId);
                    if (f.exists()) {
                        img = new Image(f.toURI().toString(), 200, 0, true, true);
                    } else {
                        img = new WritableImage(8, 8);
                    }
                } catch (Exception e) {
                    img = new WritableImage(8, 8);
                }
                row = controller.addImageMessage(img, filename + " • " + UtilHandler.humanBytes(bytes), false);
                row.setUserData(fileId);
            }
            case AUDIO -> {
                row = controller.addVoiceMessage(duration != null ? duration : "--:--", false, fileId);
                row.setUserData(fileId);
            }
            case VIDEO -> {
                row = controller.addVideoMessage(filename, meta, false, fileId);
                row.setUserData(fileId);
            }
            default -> {
                row = controller.addFileMessage(filename, meta, false);
                row.setUserData(fileId);
            }
        }

        if (fileId != null) {
            controller.getOutgoingFileBubbles().put(fileId, row);
        }
    }
}
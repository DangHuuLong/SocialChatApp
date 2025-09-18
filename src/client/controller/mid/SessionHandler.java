package client.controller.mid;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import server.dao.UserDAO;

import java.sql.SQLException;

import client.controller.MidController;

public class SessionHandler {
    private final MidController controller;

    public SessionHandler(MidController controller) {
        this.controller = controller;
    }

    public void onLogout() {
        try {
            if (controller.getCurrentUser() != null) UserDAO.setOnline(controller.getCurrentUser().getId(), false);
        } catch (SQLException ignored) {}
        if (controller.getConnection() != null) {
            try {
                controller.getConnection().close();
            } catch (Exception ignored) {}
            controller.setConnection(null);
        }
        controller.setCurrentUser(null);
        controller.getPendingFileEvents().clear();
        controller.getFileIdToName().clear();
        controller.getFileIdToMime().clear();
        controller.getOutgoingFileBubbles().clear();

        try {
            Stage stage = (Stage) controller.getLogoutBtn().getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();
            stage.setScene(new Scene(root));
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
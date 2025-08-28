package client.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.scene.control.Button;

public class HomeController {

    @FXML private Button logoutBtn;

    @FXML
    private void onLogout() {
        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
            Scene scene = new Scene(root);
            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

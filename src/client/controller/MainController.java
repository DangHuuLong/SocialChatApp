package client.controller;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import server.dao.UserDAO;
import javafx.geometry.Insets;

import java.sql.SQLException;           
import java.util.Optional;

import client.model.User;

public class MainController {

    @FXML private Button loginBtn;
    @FXML private Button registerBtn;

    private final UserDAO userDAO = new UserDAO(); 

    @FXML private void onLogin()    { showAuthDialog(AuthMode.LOGIN); }
    @FXML private void onRegister() { showAuthDialog(AuthMode.REGISTER); }

    private enum AuthMode { LOGIN, REGISTER }

    private void showAuthDialog(AuthMode mode) {
        Dialog<Boolean> dialog = new Dialog<>();
        dialog.setTitle(mode == AuthMode.LOGIN ? "Đăng nhập" : "Đăng ký");
        dialog.setHeaderText(mode == AuthMode.LOGIN ? "Nhập tài khoản để đăng nhập"
                                                    : "Tạo tài khoản mới");

        Stage owner = (Stage) loginBtn.getScene().getWindow();
        dialog.initOwner(owner);
        dialog.initModality(javafx.stage.Modality.WINDOW_MODAL);

        ButtonType okType = new ButtonType("Đồng ý", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(okType, ButtonType.CANCEL);

        TextField username = new TextField(); username.setPromptText("Tên đăng nhập");
        PasswordField password = new PasswordField(); password.setPromptText("Mật khẩu");
        PasswordField confirmField = (mode == AuthMode.REGISTER) ? new PasswordField() : null;
        if (confirmField != null) confirmField.setPromptText("Xác nhận mật khẩu");

        GridPane gp = new GridPane();
        gp.setHgap(10); gp.setVgap(10); gp.setPadding(new Insets(10));
        gp.addRow(0, new Label("Tên:"), username);
        gp.addRow(1, new Label("Mật khẩu:"), password);
        if (confirmField != null) gp.addRow(2, new Label("Xác nhận:"), confirmField);
        dialog.getDialogPane().setContent(gp);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(okType);
        okBtn.setDisable(true);

        Runnable validate = () -> {
            boolean valid = !username.getText().isBlank() && !password.getText().isBlank();
            if (mode == AuthMode.REGISTER) {
                valid = valid && confirmField != null && password.getText().equals(confirmField.getText());
            }
            okBtn.setDisable(!valid);
        };
        username.textProperty().addListener((o,a,b)->validate.run());
        password.textProperty().addListener((o,a,b)->validate.run());
        if (confirmField != null) confirmField.textProperty().addListener((o,a,b)->validate.run());
        validate.run();

        dialog.setResultConverter(bt -> bt == okType);
        Optional<Boolean> result = dialog.showAndWait();

        if (result.orElse(false)) {
            String u = username.getText().trim();
            String p = password.getText();

            try {
                boolean ok;
                if (mode == AuthMode.REGISTER) {
                    ok = userDAO.register(u, p);
                    if (!ok) {
                        showAlert(Alert.AlertType.WARNING, "Tên đăng nhập đã tồn tại.");
                        return;
                    }
                    showAlert(Alert.AlertType.INFORMATION, "Đăng ký thành công, vui lòng đăng nhập.");
                } else {
                    ok = userDAO.login(u, p);
                    if (!ok) {
                        showAlert(Alert.AlertType.ERROR, "Sai tài khoản hoặc mật khẩu.");
                        return;
                    }
                    User loggedIn = UserDAO.findByUsername(username.getText());	
                    goToHome(loggedIn);
                }
            } catch (SQLException e) {
                e.printStackTrace();
                showAlert(Alert.AlertType.ERROR, "Lỗi CSDL: " + e.getMessage());
            }
        }
    }

    private void showAlert(Alert.AlertType type, String msg) {
        new Alert(type, msg).showAndWait();
    }

    private void goToHome(User loggedInUser) {
        try {
            Stage stage = (Stage) loginBtn.getScene().getWindow();

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Home.fxml"));
            Parent root = loader.load();

            client.controller.HomeController homeController = loader.getController();
            homeController.setCurrentUser(loggedInUser);
            homeController.loadUsers();  

            Scene scene = new Scene(root);
            scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
            new Alert(Alert.AlertType.ERROR, "Không thể mở giao diện Home:\n" + e.getMessage()).showAndWait();
        }
    }

}

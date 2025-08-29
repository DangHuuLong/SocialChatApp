// client/controller/HomeController.java
package client.controller;

import client.model.User;
import javafx.fxml.*;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.*;
import server.dao.UserDAO;

import java.sql.SQLException;
import java.util.List;

public class HomeController {

    @FXML private VBox chatList;            
    private User currentUser;               
    
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

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    @FXML
    public void initialize() {
        // Nếu currentUser được set sau khi load, hãy gọi loadUsers() từ MainController sau khi set.
        // Nếu bạn dùng ControllerFactory để set trước, có thể gọi loadUsers() luôn ở đây.
    }

    public void loadUsers() {
        if (currentUser == null) return;
        try {
            List<User> others = UserDAO.listOthers(currentUser.getId());
            chatList.getChildren().clear();
            for (User u : others) {
                chatList.getChildren().add(createChatItem(u));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private HBox createChatItem(User u) {
        HBox row = new HBox(10);
        row.getStyleClass().add("chat-item");
        row.setPadding(new Insets(8, 8, 8, 8));

        ImageView avatar = new ImageView(new Image(
                getClass().getResource("/client/view/images/default user.png").toExternalForm()
        ));
        avatar.setFitWidth(40);
        avatar.setFitHeight(40);
        avatar.setPreserveRatio(true);
        avatar.setPickOnBounds(true);

        VBox textBox = new VBox(2);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Label name = new Label(u.getUsername());
        name.getStyleClass().add("chat-name");

        Label last = new Label(""); 
        last.getStyleClass().add("chat-last");

        textBox.getChildren().addAll(name, last);

        row.getChildren().addAll(avatar, textBox);
        return row;
    }
}

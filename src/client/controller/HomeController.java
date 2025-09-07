// client/controller/HomeController.java
package client.controller;

import client.ClientConnection;
import client.model.User;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import server.dao.UserDAO;

import java.sql.SQLException;

public class HomeController {

    // ========= FXML nodes được inject trực tiếp từ Home.fxml =========
    // Left panel
    @FXML private VBox chatList;
    @FXML private TextField searchField;

    // Mid panel (header + message area + input)
    @FXML private Label currentChatName;
    @FXML private Label currentChatStatus;
    @FXML private VBox messageContainer;
    @FXML private TextField messageField;
    @FXML private Button logoutBtn;

    // Right panel
    @FXML private Label infoName;
    @FXML private Label chatStatus;
    
    @FXML private StackPane centerStack;
    @FXML private VBox centerContent;
    @FXML private VBox centerEmpty;

    @FXML private StackPane rightStack;
    @FXML private VBox rightContent;
    @FXML private VBox rightEmpty;


    // ========= Controllers con sau khi tách =========
    private final LeftController leftCtrl  = new LeftController();
    private final MidController  midCtrl   = new MidController();
    private final RightController rightCtrl = new RightController();

    // ========= State chung =========
    private User currentUser;
    private ClientConnection connection;

    // ========= Lifecycle =========
    @FXML
    private void initialize() {
        // Bind các node cho từng controller con
        leftCtrl.bind(chatList, searchField);
        rightCtrl.bind(infoName, chatStatus);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField, logoutBtn);
        midCtrl.setRightController(rightCtrl);

        // Khi click 1 đoạn chat ở panel trái -> mở hội thoại ở giữa & cập nhật panel phải
        leftCtrl.setOnOpenConversation(user -> {
            toggleCenterEmpty(false);
            toggleRightEmpty(false);
            midCtrl.openConversation(user);
        });
        
        toggleCenterEmpty(true);
        toggleRightEmpty(true);
    }
    
    private void toggleCenterEmpty(boolean showEmpty) {
        centerEmpty.setVisible(showEmpty);
        centerEmpty.setManaged(showEmpty);
        centerContent.setVisible(!showEmpty);
        centerContent.setManaged(!showEmpty);
    }
    private void toggleRightEmpty(boolean showEmpty) {
        rightEmpty.setVisible(showEmpty);
        rightEmpty.setManaged(showEmpty);
        rightContent.setVisible(!showEmpty);
        rightContent.setManaged(!showEmpty);
    }


    // ========= Wiring từ màn trước (MainController) =========
    public void setCurrentUser(User user) {
        this.currentUser = user;
        leftCtrl.setCurrentUser(user);
        midCtrl.setCurrentUser(user);
    }

    public void setConnection(ClientConnection conn) {
        this.connection = conn;
        midCtrl.setConnection(conn);
    }

    /** Gọi hàm này sau khi setCurrentUser/ setConnection để load danh sách user và start polling presence */
    public void reloadAll() {
        leftCtrl.reloadAll();
    }
    
    public void searchUsers() {
    	leftCtrl.searchUsers(searchField.getText());
    }

    // ========= Handler được gọi từ FXML: onAction="#onLogout" =========
    @FXML
    private void onLogout() {
        // 1) Cập nhật trạng thái offline trong DB
        try {
            if (currentUser != null) {
                UserDAO.setOnline(currentUser.getId(), false);
            }
        } catch (SQLException ignored) {}

        // 2) Dừng polling presence (nếu đang chạy)
        leftCtrl.stopPolling();

        // 3) Đóng kết nối tới server
        if (connection != null) {
            try {
                connection.send("QUIT");
            } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        currentUser = null;

        // 4) Quay về màn hình đăng nhập (Main.fxml)
        try {
            Stage stage = (Stage) logoutBtn.getScene().getWindow();
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/client/view/Main.fxml"));
            Parent root = loader.load();

            Scene scene = new Scene(root);
            stage.setScene(scene);
            stage.centerOnScreen();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

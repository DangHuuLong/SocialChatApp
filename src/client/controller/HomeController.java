// client/controller/HomeController.java
package client.controller;

import client.ClientConnection;
import client.model.User;
import client.signaling.CallSignalingService;
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
    // Left panel
    @FXML private VBox chatList;
    @FXML private TextField searchField;

    // Mid panel 
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


    private final LeftController leftCtrl  = new LeftController();
    private final MidController  midCtrl   = new MidController();
    private final RightController rightCtrl = new RightController();

    private User currentUser;
    private ClientConnection connection;
    private CallSignalingService callSvc;

    @FXML
    private void initialize() {
        leftCtrl.bind(chatList, searchField);
        rightCtrl.bind(infoName, chatStatus);
        midCtrl.bind(currentChatName, currentChatStatus, messageContainer, messageField, logoutBtn);
        midCtrl.setRightController(rightCtrl);

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

    public void setCallService(CallSignalingService svc) {
        this.callSvc = svc;
        this.callSvc.setListener(midCtrl);

        midCtrl.setCallService(this.callSvc);
    }

    public void onServerLine(String line) {
        // xử lý các message KHÔNG phải CALL_* (MSG/DM/WHO/HISTORY...) như bạn đang làm
    }
    public void onConnectionError(Exception e) {
        // báo lỗi, show dialog, v.v.
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

    public void reloadAll() {
        leftCtrl.reloadAll();
    }
    
    public void searchUsers() {
    	leftCtrl.searchUsers(searchField.getText());
    }

    // ========= Handler được gọi từ FXML: onAction="#onLogout" =========
    @FXML
    private void onLogout() {
        try {
            if (currentUser != null) {
                UserDAO.setOnline(currentUser.getId(), false);
            }
        } catch (SQLException ignored) {}

        leftCtrl.stopPolling();

        if (connection != null) {
            try {
                connection.send("QUIT");
            } catch (Exception ignored) {}
            connection.close();
            connection = null;
        }
        currentUser = null;

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

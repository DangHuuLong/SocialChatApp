package client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.Stage;

public class ClientMain extends Application {
    public static ClientConnection connection = new ClientConnection();

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Chat App - Client");
        stage.show();

        new Thread(() -> {
            boolean ok = connection.connect("127.0.0.1", 5000);
            Platform.runLater(() -> {
                if (!ok) {
                    System.err.println("❌ Cannot connect to server!");
                    new Alert(Alert.AlertType.WARNING, "Không thể kết nối server.").show();
                } else {
                    System.out.println("✅ Connected to server.");
                }
            });
        }, "socket-connector").start();

        stage.setOnCloseRequest(e -> {
            if (connection != null) connection.close();
            Platform.exit();
        });
    }

    @Override
    public void stop() {
        if (connection != null) connection.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

package client;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ClientMain extends Application {
    public static ClientConnection connection;

    @Override
    public void start(Stage stage) throws Exception {
        connection = new ClientConnection();
        boolean ok = connection.connect("127.0.0.1", 5000); 
        if (!ok) {
            System.err.println("❌ Cannot connect to server!");
        }

        // ✅ Load UI
        Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Chat App - Client");
        stage.show();
    }

    @Override
    public void stop() {
        if (connection != null) {
            connection.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}

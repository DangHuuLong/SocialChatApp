package client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ClientMain extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("/client/view/Main.fxml"));
        Scene scene = new Scene(root);
        scene.getStylesheets().add(getClass().getResource("/client/view/chat.css").toExternalForm());
        stage.setScene(scene);
        stage.setTitle("Chat App - Client");
        stage.show();

        stage.setOnCloseRequest(e -> {
            Platform.exit();
        });
    }
    
    public static void main(String[] args) {
        launch(args);
    }
}

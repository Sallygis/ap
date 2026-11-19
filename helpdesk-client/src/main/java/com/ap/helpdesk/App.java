package com.ap.helpdesk;

import com.ap.helpdesk.managers.UserManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/** Opens the main JavaFX window and shows the sign-in screen. */
public class App extends Application {
    public static final String WINDOW_TITLE = "The University Help Desk & Ticketing System";

    // Multiple signed-in clients can write logs without sharing a rolling file.
    static {
        if (System.getProperty("helpdesk.log.name") == null) {
            System.setProperty("helpdesk.log.name", "client-" + ProcessHandle.current().pid());
        }
    }

    private static final Logger LOG = LogManager.getLogger(App.class);

    private static Scene scene;

    @Override
    public void start(Stage stage) {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                LOG.error("Unhandled client failure on {}", thread.getName(), failure));
        LOG.info("Help desk client started");
        scene = new Scene(new StackPane(), 850, 560);
        stage.setScene(scene);
        stage.setTitle(WINDOW_TITLE);
        new UserManager(scene).showLogin();
        stage.show();
    }

    static void setRoot(String fxml) throws IOException {
        scene.setRoot(loadFXML(fxml));
    }

    private static Parent loadFXML(String fxml) throws IOException {
        FXMLLoader fxmlLoader = new FXMLLoader(App.class.getResource(fxml + ".fxml"));
        return fxmlLoader.load();
    }

    public static void main(String[] args) {
        launch();
    }

}
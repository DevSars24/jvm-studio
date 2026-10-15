package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.web.WebDashboardServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JVM Studio JavaFX Application entry point.
 */
public class MainApp extends Application {
    private static final Logger logger = LoggerFactory.getLogger(MainApp.class);
    private AppContext appContext;
    private WebDashboardServer webDashboardServer;

    @Override
    public void init() throws Exception {
        logger.info("Initializing JVM Studio application...");
        this.appContext = new AppContext();

        try {
            int port = 8080;
            this.webDashboardServer = new WebDashboardServer(appContext, port);
            this.webDashboardServer.start();
            logger.info("JVM Studio Web Dashboard Server running on http://localhost:{}", port);
        } catch (Exception e) {
            logger.warn("Could not start embedded WebDashboardServer on port 8080: {}", e.getMessage());
        }
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            MainWindow mainWindow = new MainWindow(appContext);
            Scene scene = new Scene(mainWindow, 1260, 840);

            // Apply global CSS
            String css = getClass().getResource("/style.css").toExternalForm();
            scene.getStylesheets().add(css);

            primaryStage.setTitle("JVM Studio — Live Task & Plugin Dashboard");
            primaryStage.setMinWidth(1000);
            primaryStage.setMinHeight(680);
            primaryStage.setScene(scene);

            primaryStage.setOnCloseRequest(event -> {
                logger.info("Window close requested. Initiating graceful shutdown...");
                if (webDashboardServer != null) {
                    try {
                        webDashboardServer.close();
                    } catch (Exception ignored) {}
                }
                if (appContext != null) {
                    appContext.close();
                }
                Platform.exit();
            });

            primaryStage.show();
            logger.info("JVM Studio UI launched successfully.");
        } catch (Exception e) {
            logger.error("Failed to launch JVM Studio UI", e);
            throw e;
        }
    }

    @Override
    public void stop() throws Exception {
        if (webDashboardServer != null) {
            try {
                webDashboardServer.close();
            } catch (Exception ignored) {}
        }
        if (appContext != null) {
            appContext.close();
        }
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}

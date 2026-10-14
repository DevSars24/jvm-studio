package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.model.AppSettings;
import com.jvmstudio.core.model.CollisionPolicy;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.sql.Connection;
import java.sql.Statement;

/**
 * Settings screen. Manages thread pool concurrency, sampling intervals,
 * backup policies, history retention, and database optimization.
 */
public class SettingsView extends ScrollPane {

    private final AppContext context;

    private Spinner<Integer> spinThreadPool;
    private Spinner<Integer> spinSamplingInterval;
    private ComboBox<CollisionPolicy> comboCollision;
    private Spinner<Integer> spinRetention;
    private CheckBox cbAutoScroll;

    public SettingsView(AppContext context) {
        this.context = context;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildEngineConfigCard(),
                buildDatabaseCard()
        );

        setContent(root);
        loadCurrentSettings();
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("System Settings & Preferences");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Configure execution thread pool concurrency, telemetry rates, and local SQLite persistence options.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        Button btnSave = UiHelper.createPrimaryButton("Save Preferences", this::saveSettings);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer(), btnSave);
        return header;
    }

    private Node buildEngineConfigCard() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("Concurrency & Execution Parameters"),
                UiHelper.createSubtitle("Tuning parameters for the background worker executor and telemetry sampler.")
        );

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(12);

        // 1. Thread Pool Size
        Label lblPool = new Label("Thread Pool Worker Threads:");
        lblPool.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");
        spinThreadPool = new Spinner<>(1, 32, 4);
        spinThreadPool.setEditable(true);
        spinThreadPool.setPrefWidth(100);
        grid.add(lblPool, 0, 0);
        grid.add(spinThreadPool, 1, 0);

        // 2. Telemetry Interval
        Label lblSampling = new Label("JVM Sampling Interval (ms):");
        lblSampling.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");
        spinSamplingInterval = new Spinner<>(250, 5000, 1000, 250);
        spinSamplingInterval.setEditable(true);
        spinSamplingInterval.setPrefWidth(100);
        grid.add(lblSampling, 0, 1);
        grid.add(spinSamplingInterval, 1, 1);

        // 3. Default Collision Policy
        Label lblColl = new Label("Default File Collision Policy:");
        lblColl.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");
        comboCollision = new ComboBox<>();
        comboCollision.getItems().addAll(CollisionPolicy.values());
        grid.add(lblColl, 0, 2);
        grid.add(comboCollision, 1, 2);

        // 4. History Retention Days
        Label lblRet = new Label("History Retention (Days):");
        lblRet.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");
        spinRetention = new Spinner<>(1, 365, 30);
        spinRetention.setEditable(true);
        spinRetention.setPrefWidth(100);
        grid.add(lblRet, 0, 3);
        grid.add(spinRetention, 1, 3);

        // 5. Auto Scroll
        cbAutoScroll = new CheckBox("Automatically scroll task logs to latest entry");
        cbAutoScroll.setStyle("-fx-text-fill: #cbd5e1;");
        grid.add(cbAutoScroll, 0, 4, 2, 1);

        card.getChildren().add(grid);
        return card;
    }

    private Node buildDatabaseCard() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("Local Persistence Engine (SQLite)"),
                UiHelper.createSubtitle("Storage management for execution audits, task settings, and registered plugin JAR paths.")
        );

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);

        Label lblPathTitle = new Label("Database File Path:");
        lblPathTitle.setStyle("-fx-font-weight: 600; -fx-text-fill: #94a3b8;");
        Label lblPathVal = new Label(context.getDatabaseManager().getDbPath().toAbsolutePath().toString());
        lblPathVal.setStyle("-fx-font-family: monospace; -fx-text-fill: #f8fafc;");

        grid.add(lblPathTitle, 0, 0);
        grid.add(lblPathVal, 1, 0);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_LEFT);

        Button btnTest = UiHelper.createSecondaryButton("Test DB Connection", this::testConnection);
        Button btnVacuum = UiHelper.createSecondaryButton("Optimize & Vacuum DB", this::vacuumDatabase);

        actions.getChildren().addAll(btnTest, btnVacuum);

        card.getChildren().addAll(grid, actions);
        return card;
    }

    private void loadCurrentSettings() {
        AppSettings s = context.getSettings();
        spinThreadPool.getValueFactory().setValue(s.threadPoolSize());
        spinSamplingInterval.getValueFactory().setValue(s.jvmSamplingIntervalMs());
        comboCollision.setValue(s.defaultCollisionPolicy());
        spinRetention.getValueFactory().setValue(s.historyRetentionDays());
        cbAutoScroll.setSelected(s.autoScrollLogs());
    }

    private void saveSettings() {
        AppSettings newSettings = new AppSettings(
                spinThreadPool.getValue(),
                spinSamplingInterval.getValue(),
                comboCollision.getValue(),
                spinRetention.getValue(),
                true,
                cbAutoScroll.isSelected()
        );
        context.updateSettings(newSettings);
        UiHelper.showInfo("Preferences Saved", "Settings Updated", "Updated thread pool size and application configurations successfully.");
    }

    private void testConnection() {
        try (Connection conn = context.getDatabaseManager().getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1;");
            UiHelper.showInfo("Database Check", "Connection Active", "SQLite database connection is operational and responsive.");
        } catch (Exception ex) {
            UiHelper.showError("Database Check Failed", "Connection Error", ex.getMessage());
        }
    }

    private void vacuumDatabase() {
        UiHelper.runAsync(() -> {
            try (Connection conn = context.getDatabaseManager().getConnection();
                 Statement st = conn.createStatement()) {
                st.execute("VACUUM;");
                Platform.runLater(() -> {
                    UiHelper.showInfo("Optimization Complete", "Database Vacuumed", "SQLite database pages reindexed and space compacted.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    UiHelper.showError("Optimization Failed", "Error during VACUUM", ex.getMessage());
                });
            }
        }, null);
    }
}

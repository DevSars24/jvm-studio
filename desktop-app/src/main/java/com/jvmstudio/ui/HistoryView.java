package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskState;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Execution history screen with real-time SQLite querying, filtering,
 * detailed log inspection, and RFC-4180 CSV export.
 */
public class HistoryView extends ScrollPane {

    private final AppContext context;

    private ComboBox<String> taskFilter;
    private ComboBox<String> stateFilter;
    private TableView<TaskHistoryRecord> table;

    public HistoryView(AppContext context) {
        this.context = context;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildFilterBar(),
                buildTableSection()
        );

        setContent(root);
        refresh();
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("Execution History & Audit Records");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Persisted SQLite records of all task runs with durations, items processed, summaries, and complete logs.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        Button btnExport = UiHelper.createPrimaryButton("Export to CSV", this::exportToCsv);
        Button btnClear = UiHelper.createSecondaryButton("Clear History", this::clearHistory);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer(), btnClear, btnExport);
        return header;
    }

    private Node buildFilterBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setStyle("-fx-background-color: #131b2e; -fx-padding: 12 16; -fx-background-radius: 8;");

        Label lblTask = new Label("Task:");
        lblTask.setStyle("-fx-font-weight: 600; -fx-text-fill: #94a3b8;");

        taskFilter = new ComboBox<>();
        taskFilter.getItems().add("All Tasks");
        for (TaskDefinition td : context.getAllTaskDefinitions()) {
            taskFilter.getItems().add(td.getId());
        }
        taskFilter.setValue("All Tasks");
        taskFilter.setOnAction(e -> refresh());

        Label lblState = new Label("Status:");
        lblState.setStyle("-fx-font-weight: 600; -fx-text-fill: #94a3b8;");

        stateFilter = new ComboBox<>();
        stateFilter.getItems().addAll("All Statuses", "COMPLETED", "FAILED", "CANCELLED", "RUNNING", "PAUSED");
        stateFilter.setValue("All Statuses");
        stateFilter.setOnAction(e -> refresh());

        Button btnRefresh = UiHelper.createSecondaryButton("Refresh", this::refresh);

        bar.getChildren().addAll(lblTask, taskFilter, lblState, stateFilter, UiHelper.createSpacer(), btnRefresh);
        return bar;
    }

    private Node buildTableSection() {
        VBox card = new VBox(10);
        card.getStyleClass().add("card");
        VBox.setVgrow(card, Priority.ALWAYS);

        table = new TableView<>();
        table.setPrefHeight(500);

        TableColumn<TaskHistoryRecord, String> idCol = new TableColumn<>("Exec ID");
        idCol.setCellValueFactory(new PropertyValueFactory<>("executionId"));
        idCol.setPrefWidth(90);

        TableColumn<TaskHistoryRecord, String> nameCol = new TableColumn<>("Task Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("taskName"));
        nameCol.setPrefWidth(160);

        TableColumn<TaskHistoryRecord, String> catCol = new TableColumn<>("Category");
        catCol.setCellValueFactory(new PropertyValueFactory<>("category"));
        catCol.setPrefWidth(120);

        TableColumn<TaskHistoryRecord, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(cellData -> new SimpleStringProperty(cellData.getValue().state().name()));
        stateCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    TaskState st = TaskState.valueOf(item);
                    setGraphic(UiHelper.createStateBadge(st));
                }
            }
        });
        stateCol.setPrefWidth(110);

        TableColumn<TaskHistoryRecord, Long> durationCol = new TableColumn<>("Duration (ms)");
        durationCol.setCellValueFactory(new PropertyValueFactory<>("durationMs"));
        durationCol.setPrefWidth(110);

        TableColumn<TaskHistoryRecord, Long> itemsCol = new TableColumn<>("Items");
        itemsCol.setCellValueFactory(new PropertyValueFactory<>("itemsProcessed"));
        itemsCol.setPrefWidth(80);

        TableColumn<TaskHistoryRecord, String> summaryCol = new TableColumn<>("Summary");
        summaryCol.setCellValueFactory(new PropertyValueFactory<>("summary"));
        summaryCol.setPrefWidth(320);

        table.getColumns().addAll(idCol, nameCol, catCol, stateCol, durationCol, itemsCol, summaryCol);

        table.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && table.getSelectionModel().getSelectedItem() != null) {
                showDetailModal(table.getSelectionModel().getSelectedItem());
            }
        });

        Label hint = new Label("Double-click any row to view execution log snippet and full summary.");
        hint.getStyleClass().add("card-subtitle");

        card.getChildren().addAll(table, hint);
        return card;
    }

    public void refresh() {
        String selTask = taskFilter != null ? taskFilter.getValue() : "All Tasks";
        String selState = stateFilter != null ? stateFilter.getValue() : "All Statuses";

        String taskArg = (selTask == null || selTask.equals("All Tasks")) ? null : selTask;
        TaskState stateArg = (selState == null || selState.equals("All Statuses")) ? null : TaskState.valueOf(selState);

        UiHelper.runAsync(() -> {
            List<TaskHistoryRecord> records = context.getHistoryRepository().findFiltered(taskArg, stateArg, 200, 0);
            Platform.runLater(() -> {
                table.getItems().setAll(records);
            });
        }, null);
    }

    private void showDetailModal(TaskHistoryRecord rec) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Execution Details: " + rec.executionId());
        dialog.setHeaderText(rec.taskName() + " (" + rec.state() + ")");

        VBox content = new VBox(10);
        content.setPadding(new Insets(16));
        content.setPrefWidth(650);

        Label lblSummary = new Label("Summary: " + (rec.summary() != null ? rec.summary() : "N/A"));
        lblSummary.setWrapText(true);
        lblSummary.setStyle("-fx-font-weight: 600;");

        Label lblError = new Label(rec.errorMessage() != null ? ("Error: " + rec.errorMessage()) : "No errors reported.");
        lblError.setStyle(rec.errorMessage() != null ? "-fx-text-fill: #ef4444;" : "-fx-text-fill: #10b981;");
        lblError.setWrapText(true);

        Label lblLog = new Label("Execution Log Snippet:");
        lblLog.setStyle("-fx-font-weight: bold; -fx-text-fill: #94a3b8;");

        TextArea logArea = new TextArea(rec.logSnippet() != null && !rec.logSnippet().isBlank() ? rec.logSnippet() : "No logs available.");
        logArea.getStyleClass().add("log-terminal");
        logArea.setPrefRowCount(14);
        logArea.setEditable(false);
        logArea.setWrapText(true);

        content.getChildren().addAll(lblSummary, lblError, lblLog, logArea);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);

        try {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/style.css").toExternalForm());
        } catch (Exception ignored) {}

        dialog.showAndWait();
    }

    private void exportToCsv() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Task History to CSV");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Document (*.csv)", "*.csv"));
        fc.setInitialFileName("jvmstudio_history.csv");

        File file = fc.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        List<TaskHistoryRecord> currentRecords = table.getItems();
        UiHelper.runAsync(() -> {
            try (Writer writer = new FileWriter(file, StandardCharsets.UTF_8)) {
                context.getHistoryRepository().exportToCsv(writer, currentRecords);
                Platform.runLater(() -> {
                    UiHelper.showInfo("Export Complete", "CSV Exported Successfully",
                            "Saved " + currentRecords.size() + " record(s) to:\n" + file.getAbsolutePath());
                });
            } catch (IOException ex) {
                Platform.runLater(() -> {
                    UiHelper.showError("Export Failed", "Could not write CSV", ex.getMessage());
                });
            }
        }, null);
    }

    private void clearHistory() {
        boolean consent = UiHelper.showConfirmation("Clear Task History", "Are you sure?", "This will delete all saved task execution logs from the database.");
        if (consent) {
            UiHelper.runAsync(() -> {
                context.getHistoryRepository().clearAll();
                Platform.runLater(this::refresh);
            }, null);
        }
    }
}

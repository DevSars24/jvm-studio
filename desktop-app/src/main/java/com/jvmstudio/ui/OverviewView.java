package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.engine.TaskEngineListener;
import com.jvmstudio.core.engine.TaskHandle;
import com.jvmstudio.core.jvm.JvmMonitorService;
import com.jvmstudio.core.model.JvmMetricsSnapshot;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Overview dashboard screen providing high-level operational metrics,
 * active worker state, quick launchers, and recent activity feed.
 */
public class OverviewView extends ScrollPane implements TaskEngineListener, JvmMonitorService.MetricsListener {

    private final AppContext context;
    private final Consumer<String> screenNavigator;

    private Label lblActiveThreads;
    private Label lblHeapUsed;
    private Label lblRegisteredTasks;
    private Label lblCompletedRuns;

    private VBox activeTasksContainer;
    private VBox recentHistoryContainer;

    public OverviewView(AppContext context, Consumer<String> screenNavigator) {
        this.context = context;
        this.screenNavigator = screenNavigator;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildMetricsRow(),
                buildActiveTasksSection(),
                buildQuickLaunchSection(),
                buildRecentHistorySection()
        );

        setContent(root);

        // Register listeners
        context.getTaskEngine().addListener(this);
        context.getJvmMonitorService().addListener(this);

        refresh();
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("System Overview & Live Dashboard");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Real-time telemetry, concurrent task orchestration, and JVM runtime monitoring.");
        sub.getStyleClass().add("card-subtitle");

        titleBox.getChildren().addAll(title, sub);
        header.getChildren().addAll(titleBox, UiHelper.createSpacer());
        return header;
    }

    private Node buildMetricsRow() {
        HBox row = new HBox(16);
        row.setAlignment(Pos.CENTER);

        VBox tile1 = UiHelper.createMetricTile("Active Workers", "0", "Concurrent execution threads");
        lblActiveThreads = (Label) tile1.lookup("#val-active-workers");

        VBox tile2 = UiHelper.createMetricTile("Heap Usage", "0 MB", "JVM allocated heap memory");
        lblHeapUsed = (Label) tile2.lookup("#val-heap-usage");

        VBox tile3 = UiHelper.createMetricTile("Tasks Registered", String.valueOf(context.getAllTaskDefinitions().size()), "Built-in & external plugins");
        lblRegisteredTasks = (Label) tile3.lookup("#val-tasks-registered");

        VBox tile4 = UiHelper.createMetricTile("Completed Runs", "0", "Historical executions recorded");
        lblCompletedRuns = (Label) tile4.lookup("#val-completed-runs");

        row.getChildren().addAll(tile1, tile2, tile3, tile4);
        return row;
    }

    private Node buildActiveTasksSection() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(
                UiHelper.createTitle("Active Running Tasks"),
                UiHelper.createSpacer(),
                UiHelper.createSecondaryButton("View All Tasks", () -> screenNavigator.accept("Tasks"))
        );

        activeTasksContainer = new VBox(10);
        card.getChildren().addAll(header, activeTasksContainer);
        return card;
    }

    private Node buildQuickLaunchSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        card.getChildren().add(UiHelper.createTitle("Quick Task Launchers"));

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(14);

        List<TaskDefinition> tasks = context.getAllTaskDefinitions();
        int col = 0;
        int row = 0;

        for (TaskDefinition task : tasks) {
            VBox item = new VBox(8);
            item.getStyleClass().add("metric-tile");
            item.setPadding(new Insets(14));
            GridPane.setHgrow(item, Priority.ALWAYS);

            HBox top = new HBox(8);
            top.setAlignment(Pos.CENTER_LEFT);
            Label name = new Label(task.getName());
            name.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff;");
            top.getChildren().addAll(name, UiHelper.createSpacer(), UiHelper.createStateBadge(TaskState.READY));

            Label desc = new Label(task.getDescription());
            desc.getStyleClass().add("card-subtitle");
            desc.setWrapText(true);
            desc.setMaxHeight(36);

            HBox actions = new HBox(8);
            actions.setAlignment(Pos.CENTER_RIGHT);
            Button btnConfigure = UiHelper.createSecondaryButton("Configure & Run", () -> {
                screenNavigator.accept("Tasks");
            });
            actions.getChildren().add(btnConfigure);

            item.getChildren().addAll(top, desc, actions);
            grid.add(item, col, row);

            col++;
            if (col > 1) {
                col = 0;
                row++;
            }
        }

        card.getChildren().add(grid);
        return card;
    }

    private Node buildRecentHistorySection() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(
                UiHelper.createTitle("Recent Execution Log"),
                UiHelper.createSpacer(),
                UiHelper.createSecondaryButton("View Full History", () -> screenNavigator.accept("History"))
        );

        recentHistoryContainer = new VBox(8);
        card.getChildren().addAll(header, recentHistoryContainer);
        return card;
    }

    public void refresh() {
        Platform.runLater(() -> {
            int active = context.getTaskEngine().getActiveTaskCount();
            if (lblActiveThreads != null) {
                lblActiveThreads.setText(active + " / " + context.getTaskEngine().getPoolSize());
            }

            if (lblRegisteredTasks != null) {
                lblRegisteredTasks.setText(String.valueOf(context.getAllTaskDefinitions().size()));
            }

            List<TaskHistoryRecord> recent = context.getHistoryRepository().findAllRecent(6);
            if (lblCompletedRuns != null) {
                lblCompletedRuns.setText(String.valueOf(recent.size()));
            }

            // Update active tasks list
            activeTasksContainer.getChildren().clear();
            List<TaskHandle> activeHandles = context.getTaskEngine().getAllActiveTasks();
            if (activeHandles.isEmpty()) {
                Label empty = new Label("No background tasks are currently running. Launch a task below or from the Tasks screen.");
                empty.getStyleClass().add("card-subtitle");
                activeTasksContainer.getChildren().add(empty);
            } else {
                for (TaskHandle handle : activeHandles) {
                    activeTasksContainer.getChildren().add(buildActiveTaskRow(handle));
                }
            }

            // Update recent history list
            recentHistoryContainer.getChildren().clear();
            if (recent.isEmpty()) {
                Label empty = new Label("No executions recorded yet. Execute any built-in task to see live history.");
                empty.getStyleClass().add("card-subtitle");
                recentHistoryContainer.getChildren().add(empty);
            } else {
                for (TaskHistoryRecord rec : recent) {
                    recentHistoryContainer.getChildren().add(buildHistoryRow(rec));
                }
            }
        });
    }

    private Node buildActiveTaskRow(TaskHandle handle) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: #162032; -fx-padding: 10 14; -fx-background-radius: 8;");

        VBox info = new VBox(4);
        HBox.setHgrow(info, Priority.ALWAYS);
        Label title = new Label(handle.getTaskName() + " (" + handle.getExecutionId() + ")");
        title.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label msg = new Label(handle.getMessage());
        msg.getStyleClass().add("card-subtitle");

        ProgressBar pb = new ProgressBar(handle.getProgress());
        pb.setMaxWidth(Double.MAX_VALUE);

        info.getChildren().addAll(title, pb, msg);

        Label badge = UiHelper.createStateBadge(handle.getState());

        Button btnCancel = UiHelper.createDangerButton("Cancel", () -> {
            context.getTaskEngine().cancelTask(handle.getTaskId());
        });

        row.getChildren().addAll(info, badge, btnCancel);
        return row;
    }

    private Node buildHistoryRow(TaskHistoryRecord rec) {
        HBox row = new HBox(12);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setStyle("-fx-background-color: #0e1524; -fx-padding: 8 12; -fx-background-radius: 6;");

        VBox titleBox = new VBox(2);
        HBox.setHgrow(titleBox, Priority.ALWAYS);
        Label name = new Label(rec.taskName() + " [" + rec.executionId() + "]");
        name.setStyle("-fx-font-weight: 600; -fx-text-fill: #f8fafc;");

        Label sub = new Label(rec.summary() != null ? rec.summary() : "No summary");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(name, sub);

        Label duration = new Label(rec.durationMs() + " ms");
        duration.getStyleClass().add("card-subtitle");

        Label badge = UiHelper.createStateBadge(rec.state());

        row.getChildren().addAll(titleBox, duration, badge);
        return row;
    }

    @Override
    public void onMetricsSampled(JvmMetricsSnapshot snapshot) {
        Platform.runLater(() -> {
            if (lblHeapUsed != null) {
                lblHeapUsed.setText(String.format(Locale.ROOT, "%.1f MB", snapshot.getHeapUsedMb()));
            }
        });
    }

    @Override
    public void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
        refresh();
    }

    @Override
    public void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message) {
        // Handled reactively
    }

    @Override
    public void onTaskLogAppended(String taskId, String executionId, String logLine) {
        // Overview doesn't display raw logs
    }
}

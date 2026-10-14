package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.engine.TaskEngineListener;
import com.jvmstudio.core.engine.TaskHandle;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskSchedule;
import com.jvmstudio.core.model.TaskScheduleType;
import com.jvmstudio.core.model.TaskState;
import com.jvmstudio.core.plugins.PluginManager;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tasks execution screen. Allows real-time configuration, execution,
 * cooperative pause/resume, cooperative cancellation, and scheduling of all tasks.
 */
public class TasksView extends ScrollPane implements TaskEngineListener, PluginManager.PluginLifecycleListener {

    private final AppContext context;
    private final VBox cardsContainer;
    private final Map<String, TaskCardComponent> cardComponents = new ConcurrentHashMap<>();

    public TasksView(AppContext context) {
        this.context = context;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("Task Orchestration & Execution Engine");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Configure parameters, trigger concurrent executions, monitor live streaming logs, and schedule recurring tasks.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer());

        cardsContainer = new VBox(20);

        root.getChildren().addAll(header, cardsContainer);
        setContent(root);

        // Build cards for all current tasks
        rebuildTaskCards();

        // Listen for engine and plugin events
        context.getTaskEngine().addListener(this);
        context.getPluginManager().addListener(this);
    }

    private void rebuildTaskCards() {
        Platform.runLater(() -> {
            cardsContainer.getChildren().clear();
            cardComponents.clear();

            List<TaskDefinition> tasks = context.getAllTaskDefinitions();
            for (TaskDefinition def : tasks) {
                TaskCardComponent card = new TaskCardComponent(context, def);
                cardComponents.put(def.getId(), card);
                cardsContainer.getChildren().add(card.getNode());
            }
        });
    }

    @Override
    public void onPluginLoaded(TaskDefinition taskDef) {
        rebuildTaskCards();
    }

    @Override
    public void onPluginUnloaded(String pluginId) {
        rebuildTaskCards();
    }

    @Override
    public void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
        TaskCardComponent card = cardComponents.get(taskId);
        if (card != null) {
            Platform.runLater(() -> card.handleStateChanged(newState, message));
        }
    }

    @Override
    public void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message) {
        TaskCardComponent card = cardComponents.get(taskId);
        if (card != null) {
            Platform.runLater(() -> card.handleProgressUpdated(workDone, totalWork, message));
        }
    }

    @Override
    public void onTaskLogAppended(String taskId, String executionId, String logLine) {
        TaskCardComponent card = cardComponents.get(taskId);
        if (card != null) {
            Platform.runLater(() -> card.appendLog(logLine));
        }
    }

    /**
     * Individual task card handling parameter inputs, actions, and progress.
     */
    private static class TaskCardComponent {
        private final AppContext context;
        private final TaskDefinition taskDef;
        private final VBox cardNode;

        private final Map<String, Object> inputControls = new HashMap<>();
        private Label lblBadge;
        private ProgressBar progressBar;
        private Label lblStatusMessage;
        private TextArea logArea;

        private Button btnRun;
        private Button btnPause;
        private Button btnResume;
        private Button btnCancel;

        private CheckBox cbSchedule;
        private Spinner<Integer> spinDelay;

        public TaskCardComponent(AppContext context, TaskDefinition taskDef) {
            this.context = context;
            this.taskDef = taskDef;

            this.cardNode = new VBox(14);
            cardNode.getStyleClass().add("card");

            buildCard();
            syncWithEngineState();
        }

        public Node getNode() {
            return cardNode;
        }

        private void buildCard() {
            // Header Row
            HBox header = new HBox(12);
            header.setAlignment(Pos.CENTER_LEFT);

            VBox titleCol = new VBox(2);
            Label name = new Label(taskDef.getName());
            name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

            Label meta = new Label(String.format("ID: %s  |  v%s  |  Author: %s  |  Category: %s%s",
                    taskDef.getId(), taskDef.getVersion(), taskDef.getAuthor(), taskDef.getCategory(),
                    taskDef.isBuiltIn() ? " (Built-in)" : " (Dynamic Plugin)"));
            meta.getStyleClass().add("card-subtitle");
            titleCol.getChildren().addAll(name, meta);

            lblBadge = UiHelper.createStateBadge(TaskState.READY);

            header.getChildren().addAll(titleCol, UiHelper.createSpacer(), lblBadge);

            // Description
            Label desc = new Label(taskDef.getDescription());
            desc.getStyleClass().add("card-subtitle");
            desc.setWrapText(true);

            // Dynamic Parameter Form
            VBox paramSection = buildParameterForm();

            // Progress & Status Row
            VBox progressSection = new VBox(6);
            progressBar = new ProgressBar(0);
            progressBar.setMaxWidth(Double.MAX_VALUE);
            progressBar.setVisible(false);

            lblStatusMessage = new Label("Ready to execute.");
            lblStatusMessage.getStyleClass().add("card-subtitle");
            progressSection.getChildren().addAll(progressBar, lblStatusMessage);

            // Scheduling Controls
            HBox scheduleBox = new HBox(10);
            scheduleBox.setAlignment(Pos.CENTER_LEFT);
            cbSchedule = new CheckBox("Run with fixed delay every (seconds):");
            cbSchedule.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");

            spinDelay = new Spinner<>(2, 3600, 15);
            spinDelay.setEditable(true);
            spinDelay.setPrefWidth(90);

            TaskSchedule currentSched = taskDef.getSchedule();
            if (currentSched != null && currentSched.enabled()) {
                cbSchedule.setSelected(true);
                spinDelay.getValueFactory().setValue((int) currentSched.delaySeconds());
            }

            cbSchedule.selectedProperty().addListener((obs, old, selected) -> {
                TaskSchedule newSched = selected ?
                        TaskSchedule.fixedDelay(spinDelay.getValue()) :
                        TaskSchedule.once();
                taskDef.setSchedule(newSched);
                context.saveTaskConfig(taskDef);
            });

            spinDelay.valueProperty().addListener((obs, old, val) -> {
                if (cbSchedule.isSelected()) {
                    taskDef.setSchedule(TaskSchedule.fixedDelay(val));
                    context.saveTaskConfig(taskDef);
                }
            });

            scheduleBox.getChildren().addAll(cbSchedule, spinDelay);

            // Action Buttons Row
            HBox actions = new HBox(10);
            actions.setAlignment(Pos.CENTER_RIGHT);

            btnRun = UiHelper.createPrimaryButton("Run Task", this::triggerRun);
            btnPause = UiHelper.createWarningButton("Pause", () -> context.getTaskEngine().pauseTask(taskDef.getId()));
            btnResume = UiHelper.createPrimaryButton("Resume", () -> context.getTaskEngine().resumeTask(taskDef.getId()));
            btnCancel = UiHelper.createDangerButton("Cancel", () -> context.getTaskEngine().cancelTask(taskDef.getId()));

            btnPause.setVisible(false);
            btnResume.setVisible(false);
            btnCancel.setVisible(false);

            Button btnToggleLog = UiHelper.createSecondaryButton("Toggle Log Output", () -> {
                logArea.setVisible(!logArea.isVisible());
                logArea.setManaged(logArea.isVisible());
            });

            actions.getChildren().addAll(scheduleBox, UiHelper.createSpacer(), btnToggleLog, btnPause, btnResume, btnCancel, btnRun);

            // Log Console
            logArea = new TextArea();
            logArea.getStyleClass().add("log-terminal");
            logArea.setPrefRowCount(7);
            logArea.setEditable(false);
            logArea.setWrapText(true);
            logArea.setVisible(false);
            logArea.setManaged(false);

            cardNode.getChildren().addAll(header, desc, paramSection, progressSection, actions, logArea);
        }

        private VBox buildParameterForm() {
            VBox box = new VBox(8);
            box.setStyle("-fx-background-color: #0e1524; -fx-padding: 12; -fx-background-radius: 8;");

            List<TaskParameterDescriptor> descriptors = taskDef.getParameterDescriptors();
            if (descriptors.isEmpty()) {
                Label noParams = new Label("No configurable parameters required.");
                noParams.getStyleClass().add("card-subtitle");
                box.getChildren().add(noParams);
                return box;
            }

            GridPane grid = new GridPane();
            grid.setHgap(12);
            grid.setVgap(8);

            Map<String, Object> currentVals = taskDef.getConfiguredParameters();
            int r = 0;

            for (TaskParameterDescriptor desc : descriptors) {
                Label lbl = new Label(desc.label() + (desc.required() ? " *" : ""));
                lbl.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");
                grid.add(lbl, 0, r);

                Object existingVal = currentVals.getOrDefault(desc.name(), desc.defaultValue());
                String strVal = existingVal != null ? String.valueOf(existingVal) : "";

                if (desc.type() == TaskParameterType.BOOLEAN) {
                    CheckBox cb = new CheckBox(desc.description());
                    cb.setStyle("-fx-text-fill: #94a3b8;");
                    cb.setSelected(Boolean.parseBoolean(strVal));
                    cb.selectedProperty().addListener((o, old, v) -> taskDef.setParameter(desc.name(), v));
                    inputControls.put(desc.name(), cb);
                    grid.add(cb, 1, r);
                } else if (desc.type() == TaskParameterType.CHOICE) {
                    ComboBox<String> combo = new ComboBox<>();
                    combo.getItems().addAll(desc.choices());
                    combo.setValue(strVal);
                    combo.valueProperty().addListener((o, old, v) -> taskDef.setParameter(desc.name(), v));
                    inputControls.put(desc.name(), combo);
                    grid.add(combo, 1, r);
                } else if (desc.type() == TaskParameterType.DIRECTORY || desc.type() == TaskParameterType.FILE) {
                    HBox fileBox = new HBox(8);
                    fileBox.setAlignment(Pos.CENTER_LEFT);
                    TextField tf = new TextField(strVal);
                    tf.setPromptText(desc.description());
                    HBox.setHgrow(tf, Priority.ALWAYS);
                    tf.textProperty().addListener((o, old, v) -> taskDef.setParameter(desc.name(), v));
                    inputControls.put(desc.name(), tf);

                    Button btnBrowse = UiHelper.createSecondaryButton("Browse...", () -> {
                        if (desc.type() == TaskParameterType.DIRECTORY) {
                            DirectoryChooser dc = new DirectoryChooser();
                            dc.setTitle("Select " + desc.label());
                            File f = dc.showDialog(cardNode.getScene().getWindow());
                            if (f != null) tf.setText(f.getAbsolutePath());
                        } else {
                            FileChooser fc = new FileChooser();
                            fc.setTitle("Select " + desc.label());
                            File f = fc.showOpenDialog(cardNode.getScene().getWindow());
                            if (f != null) tf.setText(f.getAbsolutePath());
                        }
                    });
                    fileBox.getChildren().addAll(tf, btnBrowse);
                    grid.add(fileBox, 1, r);
                } else {
                    TextField tf = new TextField(strVal);
                    tf.setPromptText(desc.description());
                    HBox.setHgrow(tf, Priority.ALWAYS);
                    tf.textProperty().addListener((o, old, v) -> taskDef.setParameter(desc.name(), v));
                    inputControls.put(desc.name(), tf);
                    grid.add(tf, 1, r);
                }
                r++;
            }

            box.getChildren().add(grid);
            return box;
        }

        private void triggerRun() {
            // Save parameters to definition & DB
            context.saveTaskConfig(taskDef);

            // Execute on background worker thread through TaskEngine
            UiHelper.runAsync(() -> {
                context.getTaskEngine().runTask(taskDef, null);
            }, err -> {
                UiHelper.showError("Task Execution Error", "Could not start task", err.getMessage());
            });
        }

        private void syncWithEngineState() {
            TaskHandle handle = context.getTaskEngine().getActiveTaskHandle(taskDef.getId());
            if (handle != null) {
                handleStateChanged(handle.getState(), handle.getMessage());
                handleProgressUpdated(handle.getContext().getLastReportedDone(), handle.getContext().getLastReportedTotal(), handle.getMessage());
            } else {
                handleStateChanged(TaskState.READY, "Ready to execute.");
            }
        }

        public void handleStateChanged(TaskState state, String message) {
            UiHelper.applyBadgeStyle(lblBadge, state);
            if (message != null && !message.isBlank()) {
                lblStatusMessage.setText(message);
            }

            boolean isRunning = (state == TaskState.RUNNING);
            boolean isPaused = (state == TaskState.PAUSED);

            progressBar.setVisible(isRunning || isPaused);
            btnRun.setDisable(isRunning || isPaused);

            btnPause.setVisible(isRunning);
            btnResume.setVisible(isPaused);
            btnCancel.setVisible(isRunning || isPaused);

            if (state == TaskState.COMPLETED || state == TaskState.FAILED || state == TaskState.CANCELLED) {
                progressBar.setVisible(false);
            }
        }

        public void handleProgressUpdated(double workDone, double totalWork, String message) {
            if (message != null && !message.isBlank()) {
                lblStatusMessage.setText(message);
            }
            if (totalWork <= 0) {
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            } else {
                progressBar.setProgress(Math.max(0.0, Math.min(1.0, workDone / totalWork)));
            }
        }

        public void appendLog(String line) {
            logArea.appendText(line + "\n");
        }
    }
}

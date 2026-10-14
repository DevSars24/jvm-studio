package com.jvmstudio.ui;

import com.jvmstudio.core.model.TaskState;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * UI utility helpers for creating consistent dark dashboard components,
 * badges, cards, metrics, and thread-safe callbacks.
 */
public final class UiHelper {
    private UiHelper() {}

    public static Label createTitle(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("card-title");
        return lbl;
    }

    public static Label createSubtitle(String text) {
        Label lbl = new Label(text);
        lbl.getStyleClass().add("card-subtitle");
        lbl.setWrapText(true);
        return lbl;
    }

    public static VBox createCard(String title, String subtitle, Node... content) {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        if (title != null && !title.isBlank()) {
            VBox header = new VBox(4);
            header.getChildren().add(createTitle(title));
            if (subtitle != null && !subtitle.isBlank()) {
                header.getChildren().add(createSubtitle(subtitle));
            }
            card.getChildren().add(header);
        }

        if (content != null && content.length > 0) {
            card.getChildren().addAll(content);
        }
        return card;
    }

    public static VBox createMetricTile(String label, String initialValue, String subText) {
        VBox tile = new VBox(6);
        tile.getStyleClass().add("metric-tile");
        HBox.setHgrow(tile, Priority.ALWAYS);

        Label lblLabel = new Label(label);
        lblLabel.getStyleClass().add("metric-label");

        Label lblVal = new Label(initialValue);
        lblVal.getStyleClass().add("metric-value");
        lblVal.setId("val-" + label.toLowerCase().replace(" ", "-"));

        Label lblSub = new Label(subText != null ? subText : "");
        lblSub.getStyleClass().add("metric-sub");

        tile.getChildren().addAll(lblLabel, lblVal, lblSub);
        return tile;
    }

    public static Label createStateBadge(TaskState state) {
        Label badge = new Label(state != null ? state.name() : "UNKNOWN");
        badge.getStyleClass().add("badge");
        applyBadgeStyle(badge, state);
        return badge;
    }

    public static void applyBadgeStyle(Label badge, TaskState state) {
        badge.getStyleClass().removeIf(s -> s.startsWith("badge-"));
        if (state == null) state = TaskState.READY;
        badge.setText(state.name());
        switch (state) {
            case READY -> badge.getStyleClass().add("badge-ready");
            case SCHEDULED -> badge.getStyleClass().add("badge-scheduled");
            case RUNNING -> badge.getStyleClass().add("badge-running");
            case PAUSED -> badge.getStyleClass().add("badge-paused");
            case COMPLETED -> badge.getStyleClass().add("badge-completed");
            case FAILED -> badge.getStyleClass().add("badge-failed");
            case CANCELLED -> badge.getStyleClass().add("badge-cancelled");
        }
    }

    public static Button createPrimaryButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-primary");
        if (action != null) {
            btn.setOnAction(e -> action.run());
        }
        return btn;
    }

    public static Button createSecondaryButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-secondary");
        if (action != null) {
            btn.setOnAction(e -> action.run());
        }
        return btn;
    }

    public static Button createWarningButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-warning");
        if (action != null) {
            btn.setOnAction(e -> action.run());
        }
        return btn;
    }

    public static Button createDangerButton(String text, Runnable action) {
        Button btn = new Button(text);
        btn.getStyleClass().add("btn-danger");
        if (action != null) {
            btn.setOnAction(e -> action.run());
        }
        return btn;
    }

    public static Region createSpacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    public static void showInfo(String title, String header, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            applyAlertStyle(alert);
            alert.showAndWait();
        });
    }

    public static void showError(String title, String header, String content) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(header);
            alert.setContentText(content);
            applyAlertStyle(alert);
            alert.showAndWait();
        });
    }

    public static boolean showConfirmation(String title, String header, String content) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(content);
        applyAlertStyle(alert);
        Optional<ButtonType> res = alert.showAndWait();
        return res.isPresent() && res.get() == ButtonType.OK;
    }

    private static void applyAlertStyle(Alert alert) {
        try {
            alert.getDialogPane().getStylesheets().add(UiHelper.class.getResource("/style.css").toExternalForm());
            alert.getDialogPane().getStyleClass().add("card");
        } catch (Exception ignored) {}
    }

    public static void runAsync(Runnable backgroundTask, Consumer<Throwable> onError) {
        Thread t = new Thread(() -> {
            try {
                backgroundTask.run();
            } catch (Throwable ex) {
                if (onError != null) {
                    Platform.runLater(() -> onError.accept(ex));
                }
            }
        }, "jvmstudio-ui-async");
        t.setDaemon(true);
        t.start();
    }
}

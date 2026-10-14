package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.plugins.PluginManager;
import com.jvmstudio.plugin.api.PluginConstants;
import com.jvmstudio.plugin.api.TaskInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

/**
 * Plugins management screen. Enables dynamic loading of external JARs at runtime,
 * reflection inspection of metadata and bytecode, and safe unloading.
 */
public class PluginsView extends ScrollPane implements PluginManager.PluginLifecycleListener {

    private final AppContext context;
    private final VBox pluginsListContainer = new VBox(12);

    public PluginsView(AppContext context) {
        this.context = context;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildSecurityNoticeCard(),
                buildLoadedPluginsSection()
        );

        setContent(root);

        context.getPluginManager().addListener(this);
        refresh();
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("Dynamic Plugin Architecture");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Load external Java task JARs at runtime without application restarts. Uses URLClassLoader, ServiceLoader, and Reflection.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        Button btnLoadJar = UiHelper.createPrimaryButton("+ Load Plugin JAR", this::promptLoadPluginJar);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer(), btnLoadJar);
        return header;
    }

    private Node buildSecurityNoticeCard() {
        VBox card = new VBox(8);
        card.setStyle("-fx-background-color: #1a1523; -fx-border-color: #4c1d95; -fx-border-radius: 10; -fx-background-radius: 10; -fx-padding: 14;");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        Label icon = new Label("SECURITY ARCHITECTURE NOTICE");
        icon.setStyle("-fx-font-weight: bold; -fx-text-fill: #a78bfa; -fx-font-size: 12px;");
        top.getChildren().add(icon);

        Label body = new Label(
                "Plugins execute arbitrary Java bytecode within the host process with your standard user permissions. " +
                "URLClassLoader provides modular isolation and dynamic loading, NOT a security sandbox. " +
                "Only load plugins from trusted authors. Host Plugin API Version: " + PluginConstants.CURRENT_API_VERSION
        );
        body.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");
        body.setWrapText(true);

        card.getChildren().addAll(top, body);
        return card;
    }

    private Node buildLoadedPluginsSection() {
        VBox card = new VBox(14);
        card.getStyleClass().add("card");

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        top.getChildren().add(UiHelper.createTitle("Installed Dynamic Plugins"));

        card.getChildren().addAll(top, pluginsListContainer);
        return card;
    }

    public void refresh() {
        Platform.runLater(() -> {
            pluginsListContainer.getChildren().clear();
            List<PluginManager.LoadedPlugin> loaded = context.getPluginManager().getLoadedPlugins();

            if (loaded.isEmpty()) {
                VBox emptyBox = new VBox(10);
                emptyBox.setAlignment(Pos.CENTER);
                emptyBox.setPadding(new Insets(24));

                Label lblEmpty = new Label("No external plugins are currently loaded.");
                lblEmpty.setStyle("-fx-font-weight: bold; -fx-text-fill: #94a3b8;");

                Label lblHint = new Label("Click '+ Load Plugin JAR' above to load a plugin (e.g. sample-plugin/target/sample-plugin-1.0.0.jar).");
                lblHint.getStyleClass().add("card-subtitle");

                emptyBox.getChildren().addAll(lblEmpty, lblHint);
                pluginsListContainer.getChildren().add(emptyBox);
                return;
            }

            for (PluginManager.LoadedPlugin plugin : loaded) {
                pluginsListContainer.getChildren().add(buildPluginCard(plugin));
            }
        });
    }

    private Node buildPluginCard(PluginManager.LoadedPlugin plugin) {
        VBox card = new VBox(12);
        card.setStyle("-fx-background-color: #0e1524; -fx-border-color: #1e293b; -fx-border-radius: 8; -fx-background-radius: 8; -fx-padding: 14;");

        // Header
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(2);
        TaskInfo info = plugin.taskInfo();
        Label name = new Label(info.name());
        name.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #ffffff;");

        Label meta = new Label(String.format("ID: %s  |  v%s  |  Author: %s  |  Target API: %s  |  Category: %s",
                info.id(), info.version(), info.author(), info.apiVersion(), info.category()));
        meta.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(name, meta);

        Button btnUnload = UiHelper.createDangerButton("Unload Plugin", () -> triggerUnloadPlugin(plugin.id()));

        header.getChildren().addAll(titleBox, UiHelper.createSpacer(), btnUnload);

        Label desc = new Label(info.description());
        desc.getStyleClass().add("card-subtitle");
        desc.setWrapText(true);

        Label jarPathLbl = new Label("JAR Location: " + plugin.jarPath().toAbsolutePath());
        jarPathLbl.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: #64748b;");

        // Reflection Inspector toggle
        TextArea reflectionArea = new TextArea();
        reflectionArea.getStyleClass().add("log-terminal");
        reflectionArea.setEditable(false);
        reflectionArea.setPrefRowCount(8);
        reflectionArea.setVisible(false);
        reflectionArea.setManaged(false);

        Button btnInspect = UiHelper.createSecondaryButton("Inspect Reflection Metadata", () -> {
            boolean show = !reflectionArea.isVisible();
            if (show && reflectionArea.getText().isEmpty()) {
                reflectionArea.setText(generateReflectionReport(plugin));
            }
            reflectionArea.setVisible(show);
            reflectionArea.setManaged(show);
        });

        card.getChildren().addAll(header, desc, jarPathLbl, btnInspect, reflectionArea);
        return card;
    }

    private String generateReflectionReport(PluginManager.LoadedPlugin plugin) {
        StringBuilder sb = new StringBuilder();
        Class<?> clazz = plugin.pluginClass();
        sb.append("=== Java Reflection Inspection: ").append(clazz.getName()).append(" ===\n");
        sb.append("ClassLoader: ").append(clazz.getClassLoader()).append("\n");
        sb.append("Parent ClassLoader: ").append(clazz.getClassLoader().getParent()).append("\n\n");

        sb.append("--- Annotations Discovered ---\n");
        for (var ann : clazz.getAnnotations()) {
            sb.append("@").append(ann.annotationType().getName()).append("\n");
        }
        sb.append("\n--- Implemented Interfaces ---\n");
        for (Class<?> iface : clazz.getInterfaces()) {
            sb.append("implements ").append(iface.getName()).append("\n");
        }
        sb.append("\n--- Declared Methods ---\n");
        for (Method m : clazz.getDeclaredMethods()) {
            sb.append(Modifier.toString(m.getModifiers())).append(" ")
              .append(m.getReturnType().getSimpleName()).append(" ")
              .append(m.getName()).append("(");
            var params = m.getParameterTypes();
            for (int i = 0; i < params.length; i++) {
                sb.append(params[i].getSimpleName());
                if (i < params.length - 1) sb.append(", ");
            }
            sb.append(")\n");
        }
        return sb.toString();
    }

    private void promptLoadPluginJar() {
        boolean consent = UiHelper.showConfirmation(
                "Dynamic Code Execution Confirmation",
                "Load Untrusted Java Archive?",
                "You are loading external Java bytecode into the running JVM.\n" +
                "The plugin will have execution privileges within this host process.\n\n" +
                "Do you want to select a plugin JAR?"
        );

        if (!consent) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Select Task Plugin JAR");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Java Archive (*.jar)", "*.jar"));

        File jarFile = fc.showOpenDialog(getScene().getWindow());
        if (jarFile == null) return;

        Path path = jarFile.toPath();
        UiHelper.runAsync(() -> {
            try {
                var def = context.getPluginManager().loadPluginJar(path);
                Platform.runLater(() -> {
                    UiHelper.showInfo("Plugin Loaded", "Success",
                            "Plugin '" + def.getName() + "' (ID: " + def.getId() + ") loaded successfully!\n" +
                            "A new task card is now available in the Tasks screen.");
                    refresh();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    UiHelper.showError("Plugin Loading Failed", "Could not load plugin JAR", ex.getMessage());
                });
            }
        }, err -> {
            UiHelper.showError("Plugin Error", "Unexpected failure", err.getMessage());
        });
    }

    private void triggerUnloadPlugin(String pluginId) {
        try {
            String msg = context.getPluginManager().unloadPlugin(pluginId, context.getTaskEngine());
            UiHelper.showInfo("Plugin Unloaded", "Success", msg);
            refresh();
        } catch (Exception ex) {
            UiHelper.showError("Cannot Unload Plugin", "Failed to unload plugin", ex.getMessage());
        }
    }

    @Override
    public void onPluginLoaded(com.jvmstudio.core.model.TaskDefinition taskDef) {
        refresh();
    }

    @Override
    public void onPluginUnloaded(String pluginId) {
        refresh();
    }
}

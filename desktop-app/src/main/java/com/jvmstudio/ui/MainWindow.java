package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.Map;

/**
 * Main application window layout containing persistent sidebar navigation,
 * top global telemetry header, and dynamic central screen switching.
 */
public class MainWindow extends BorderPane {

    private final AppContext context;
    private final StackPane contentStack;
    private final Map<String, Node> screenCache = new HashMap<>();
    private final Map<String, Button> navButtons = new HashMap<>();

    private Label lblHeaderScreenTitle;
    private Label lblHeaderWorkers;

    public MainWindow(AppContext context) {
        this.context = context;

        // Apply base style
        getStyleClass().add("root");

        // 1. Sidebar
        VBox sidebar = buildSidebar();
        setLeft(sidebar);

        // 2. Center Content Area
        contentStack = new StackPane();
        contentStack.getStyleClass().add("content-area");

        // 3. Top Header
        HBox topHeader = buildTopHeader();

        VBox centerWrapper = new VBox(topHeader, contentStack);
        VBox.setVgrow(contentStack, Priority.ALWAYS);
        setCenter(centerWrapper);

        // Navigate to default screen
        navigateTo("Overview");
    }

    private VBox buildSidebar() {
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(240);
        sidebar.setMinWidth(220);

        // Brand / Logo Header
        VBox brand = new VBox(2);
        Label title = new Label("JVM Studio");
        title.getStyleClass().add("sidebar-brand-title");

        Label sub = new Label("LIVE TASK & PLUGIN DASHBOARD");
        sub.getStyleClass().add("sidebar-brand-sub");

        brand.getChildren().addAll(title, sub);

        // Navigation Items
        VBox navItems = new VBox(6);
        navItems.getChildren().addAll(
                createNavButton("Overview", "Overview"),
                createNavButton("Tasks", "Tasks & Orchestrator"),
                createNavButton("Plugins", "Dynamic Plugins"),
                createNavButton("JVM Monitor", "JVM Monitor & GC"),
                createNavButton("History", "Execution History"),
                createNavButton("Learning Lab", "Java vs C++ Lab"),
                createNavButton("Settings", "System Settings")
        );

        // Bottom Footer
        VBox footer = new VBox(6);
        footer.setStyle("-fx-background-color: #080c16; -fx-padding: 12; -fx-background-radius: 8; -fx-border-color: #1a2234; -fx-border-radius: 8;");

        Label envLabel = new Label("Java 21  |  HotSpot VM");
        envLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #60a5fa; -fx-font-size: 11px;");

        Label archLabel = new Label(System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")");
        archLabel.setStyle("-fx-text-fill: #64748b; -fx-font-size: 10px;");

        footer.getChildren().addAll(envLabel, archLabel);

        sidebar.getChildren().addAll(brand, navItems, UiHelper.createSpacer(), footer);
        return sidebar;
    }

    private Button createNavButton(String screenKey, String labelText) {
        Button btn = new Button(labelText);
        btn.getStyleClass().add("nav-button");
        btn.setMaxWidth(Double.MAX_VALUE);
        btn.setOnAction(e -> navigateTo(screenKey));
        navButtons.put(screenKey, btn);
        return btn;
    }

    private HBox buildTopHeader() {
        HBox header = new HBox(16);
        header.getStyleClass().add("top-header");
        header.setAlignment(Pos.CENTER_LEFT);

        lblHeaderScreenTitle = new Label("Overview");
        lblHeaderScreenTitle.getStyleClass().add("header-title");

        lblHeaderWorkers = new Label("Engine: Ready");
        lblHeaderWorkers.setStyle("-fx-background-color: #172033; -fx-text-fill: #94a3b8; -fx-padding: 4 10; -fx-background-radius: 12; -fx-font-size: 11px;");

        Button btnQuickGc = UiHelper.createWarningButton("Request GC", () -> {
            System.gc();
            UiHelper.showInfo("GC Request", "Dispatched System.gc()", "Garbage collection requested to the JVM runtime.");
        });

        header.getChildren().addAll(lblHeaderScreenTitle, lblHeaderWorkers, UiHelper.createSpacer(), btnQuickGc);
        return header;
    }

    public void navigateTo(String screenKey) {
        // Update active nav button
        for (Map.Entry<String, Button> entry : navButtons.entrySet()) {
            entry.getValue().getStyleClass().remove("nav-button-active");
            if (entry.getKey().equalsIgnoreCase(screenKey)) {
                entry.getValue().getStyleClass().add("nav-button-active");
            }
        }

        lblHeaderScreenTitle.setText(screenKey);

        // Lazy-load and cache screens
        Node screen = screenCache.computeIfAbsent(screenKey, key -> {
            return switch (key) {
                case "Overview" -> new OverviewView(context, this::navigateTo);
                case "Tasks" -> new TasksView(context);
                case "Plugins" -> new PluginsView(context);
                case "JVM Monitor" -> new JvmMonitorView(context);
                case "History" -> new HistoryView(context);
                case "Learning Lab" -> new LearningLabView(context, this::navigateTo);
                case "Settings" -> new SettingsView(context);
                default -> new OverviewView(context, this::navigateTo);
            };
        });

        contentStack.getChildren().setAll(screen);
    }
}

package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.jvm.JvmMonitorService;
import com.jvmstudio.core.model.JvmMetricsSnapshot;
import com.jvmstudio.core.model.TaskDefinition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Educational Java vs C++ Learning Lab.
 * Connects 5 key runtime mechanisms to real, live interactive behaviors and accurate architectural comparisons.
 */
public class LearningLabView extends ScrollPane implements JvmMonitorService.MetricsListener {

    private final AppContext context;
    private final Consumer<String> screenNavigator;

    private Label lblObservedClassLoading;
    private Label lblObservedReflection;
    private Label lblObservedConcurrency;
    private Label lblObservedGc;
    private Label lblObservedPortability;

    // Small allocation holder for lab demonstration
    private final List<byte[]> labAllocations = new ArrayList<>();

    public LearningLabView(AppContext context, Consumer<String> screenNavigator) {
        this.context = context;
        this.screenNavigator = screenNavigator;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(22);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildLabCard1DynamicClassLoading(),
                buildLabCard2Reflection(),
                buildLabCard3Concurrency(),
                buildLabCard4GarbageCollection(),
                buildLabCard5Portability()
        );

        setContent(root);
        context.getJvmMonitorService().addListener(this);
        updateLiveObservations();
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("Java vs C++ Architectural Learning Lab");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Explore Java runtime architecture through real, interactive experiments and objective, nuanced comparisons with C++.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer());
        return header;
    }

    // Module 1: Dynamic Class Loading
    private Node buildLabCard1DynamicClassLoading() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("1. Dynamic Class Loading & Plugin Isolation"),
                UiHelper.createSubtitle("How the JVM loads, verifies, and links classes on demand at runtime without stopping the process.")
        );

        GridPane grid = buildComparisonGrid(
                "Java Mechanism (JVM)",
                "The JVM utilizes a hierarchical ClassLoader delegation model (Bootstrap -> Platform -> App -> URLClassLoader). " +
                "Bytecode (.class) is verified, prepared, and resolved into Metaspace dynamically at runtime. " +
                "Applications can load arbitrary JARs on demand and isolate versions through child classloaders.",

                "C++ Comparison",
                "C++ can also dynamically load shared libraries at runtime using OS-specific APIs (dlopen/dlsym on POSIX, LoadLibrary/GetProcAddress on Windows). " +
                "However, C++ lacks a standardized binary ABI across compilers. Differences in name mangling, standard library implementations, " +
                "or compiler flags can cause crashes. Functions must often be exported via extern \"C\"."
        );

        HBox liveBox = buildLiveBox();
        Button btnAction = UiHelper.createPrimaryButton("Open Plugins Screen", () -> screenNavigator.accept("Plugins"));
        lblObservedClassLoading = new Label("Reading classloader state...");
        lblObservedClassLoading.getStyleClass().add("card-subtitle");
        liveBox.getChildren().addAll(btnAction, UiHelper.createSpacer(), lblObservedClassLoading);

        card.getChildren().addAll(grid, liveBox);
        return card;
    }

    // Module 2: Reflection & Metadata
    private Node buildLabCard2Reflection() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("2. Reflection & Runtime Annotations"),
                UiHelper.createSubtitle("Inspecting class structures, annotations, methods, and invoking code dynamically without compile-time knowledge.")
        );

        GridPane grid = buildComparisonGrid(
                "Java Mechanism (JVM)",
                "Java bytecode retains rich symbolic metadata in the class file's constant pool. " +
                "Using java.lang.reflect and annotations (@Retention(RUNTIME)), frameworks can inspect fields, " +
                "discover plugins, read metadata, and dynamically invoke methods reflectively at runtime.",

                "C++ Comparison",
                "Standard C++ offers minimal runtime type information (RTTI via typeid and dynamic_cast) and no built-in reflection. " +
                "Enumerating member methods or reading arbitrary metadata requires preprocessors, external code generators (e.g. Qt's MOC), " +
                "or upcoming reflection proposals (C++26). C++ prioritizes zero-overhead compile-time abstractions over runtime reflection."
        );

        HBox liveBox = buildLiveBox();
        Button btnInspect = UiHelper.createPrimaryButton("Inspect Loaded Tasks Reflectively", this::runReflectionDemo);
        lblObservedReflection = new Label("Click button to inspect registered tasks reflectively.");
        lblObservedReflection.getStyleClass().add("card-subtitle");
        liveBox.getChildren().addAll(btnInspect, UiHelper.createSpacer(), lblObservedReflection);

        card.getChildren().addAll(grid, liveBox);
        return card;
    }

    // Module 3: Concurrency
    private Node buildLabCard3Concurrency() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("3. Concurrent Background Processing & Thread Coordination"),
                UiHelper.createSubtitle("Executing CPU & I/O tasks on dedicated worker threads while keeping the JavaFX Application Thread responsive.")
        );

        GridPane grid = buildComparisonGrid(
                "Java Mechanism (JVM)",
                "Java features a formalized Memory Model (JSR-133), built-in primitives (synchronized, volatile), " +
                "and java.util.concurrent (ThreadPoolExecutor, Future, ReentrantLock). " +
                "UI updates are safely marshaled to the JavaFX Application Thread via Platform.runLater.",

                "C++ Comparison",
                "C++11 introduced std::thread, std::async, std::mutex, and a formalized memory model. " +
                "C++ threads map directly to OS pthreads or Win32 threads. However, C++ does not provide a standard " +
                "thread pool in the standard library, requiring third-party libraries (Boost, Intel TBB, or custom queues)."
        );

        HBox liveBox = buildLiveBox();
        Button btnConcur = UiHelper.createPrimaryButton("Run Concurrency Demo", () -> {
            // Launch folder analyzer or memory demo
            TaskDefinition def = context.getTaskDefinition("memory-allocation-demo");
            if (def != null) {
                context.getTaskEngine().runTask(def, null);
                lblObservedConcurrency.setText("Started concurrent Memory Demo. Notice UI remains 100% fluid!");
            }
        });
        lblObservedConcurrency = new Label("Active workers: 0");
        lblObservedConcurrency.getStyleClass().add("card-subtitle");
        liveBox.getChildren().addAll(btnConcur, UiHelper.createSpacer(), lblObservedConcurrency);

        card.getChildren().addAll(grid, liveBox);
        return card;
    }

    // Module 4: Garbage Collection
    private Node buildLabCard4GarbageCollection() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("4. Automatic Garbage Collection vs Deterministic RAII"),
                UiHelper.createSubtitle("Contrasting automatic generational memory management with compile-time deterministic resource release.")
        );

        GridPane grid = buildComparisonGrid(
                "Java Mechanism (JVM)",
                "Java eliminates manual malloc/free or delete. The Garbage Collector (G1, ZGC) tracks object reachability " +
                "from GC roots and sweeps unreferenced memory. However, GC does NOT replace closing OS resources (files, sockets); " +
                "these still require try-with-resources (AutoCloseable). System.gc() is only an observational request.",

                "C++ Comparison",
                "C++ relies on RAII (Resource Acquisition Is Initialization) and smart pointers (std::unique_ptr, std::shared_ptr). " +
                "Destructors run deterministically the moment an object leaves scope, releasing memory, files, and mutexes immediately " +
                "without GC pause overhead or runtime memory tracking."
        );

        HBox liveBox = buildLiveBox();
        Button btnAlloc = UiHelper.createPrimaryButton("+ Allocate 30 MB", () -> {
            try {
                byte[] chunk = new byte[30 * 1024 * 1024];
                chunk[0] = 1;
                synchronized (labAllocations) {
                    labAllocations.add(chunk);
                }
                lblObservedGc.setText("Allocated 30 MB. Active lab references: " + labAllocations.size());
            } catch (OutOfMemoryError e) {
                lblObservedGc.setText("Safe heap bound reached.");
            }
        });
        Button btnFree = UiHelper.createSecondaryButton("Release References", () -> {
            synchronized (labAllocations) {
                labAllocations.clear();
            }
            lblObservedGc.setText("References released. Memory is now unreachable and eligible for GC.");
        });
        Button btnGc = UiHelper.createWarningButton("Request GC", () -> {
            System.gc();
            lblObservedGc.setText("System.gc() requested. Check JVM Monitor for GC counter increment.");
        });

        lblObservedGc = new Label("Observing heap state...");
        lblObservedGc.getStyleClass().add("card-subtitle");

        liveBox.getChildren().addAll(btnAlloc, btnFree, btnGc, UiHelper.createSpacer(), lblObservedGc);

        card.getChildren().addAll(grid, liveBox);
        return card;
    }

    // Module 5: Portability
    private Node buildLabCard5Portability() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("5. JVM Bytecode Portability vs Native Machine Compilation"),
                UiHelper.createSubtitle("Comparing intermediate bytecode execution on HotSpot VM against platform-targeted native binaries.")
        );

        GridPane grid = buildComparisonGrid(
                "Java Mechanism (JVM)",
                "Java compiles source into platform-agnostic bytecode (.class) adhering to the Java Virtual Machine Specification. " +
                "The HotSpot JVM interprets bytecode and dynamically compiles hot paths to native assembly via JIT compilers (C1/C2). " +
                "Bytecode runs unchanged across Windows, macOS, and Linux on any compatible JVM.",

                "C++ Comparison",
                "C++ compiles directly to native machine code (x86_64, ARM64) and links against specific OS C runtimes. " +
                "This achieves maximum raw performance and minimal memory footprint with zero VM startup overhead. " +
                "However, cross-platform distribution requires compiling separate binaries for every target operating system and CPU architecture."
        );

        HBox liveBox = buildLiveBox();
        lblObservedPortability = new Label("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch") + " | Java: " + System.getProperty("java.version"));
        lblObservedPortability.setStyle("-fx-font-family: monospace; -fx-text-fill: #94a3b8;");
        liveBox.getChildren().addAll(lblObservedPortability);

        card.getChildren().addAll(grid, liveBox);
        return card;
    }

    private GridPane buildComparisonGrid(String javaHeader, String javaText, String cppHeader, String cppText) {
        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);

        VBox javaBox = new VBox(6);
        javaBox.setStyle("-fx-background-color: #0e1524; -fx-padding: 12; -fx-background-radius: 8;");
        GridPane.setHgrow(javaBox, Priority.ALWAYS);
        Label jh = new Label(javaHeader);
        jh.setStyle("-fx-font-weight: bold; -fx-text-fill: #60a5fa;");
        Label jt = new Label(javaText);
        jt.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");
        jt.setWrapText(true);
        javaBox.getChildren().addAll(jh, jt);

        VBox cppBox = new VBox(6);
        cppBox.setStyle("-fx-background-color: #0e1524; -fx-padding: 12; -fx-background-radius: 8;");
        GridPane.setHgrow(cppBox, Priority.ALWAYS);
        Label ch = new Label(cppHeader);
        ch.setStyle("-fx-font-weight: bold; -fx-text-fill: #a78bfa;");
        Label ct = new Label(cppText);
        ct.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");
        ct.setWrapText(true);
        cppBox.getChildren().addAll(ch, ct);

        grid.add(javaBox, 0, 0);
        grid.add(cppBox, 1, 0);
        return grid;
    }

    private HBox buildLiveBox() {
        HBox box = new HBox(12);
        box.setAlignment(Pos.CENTER_LEFT);
        box.setStyle("-fx-background-color: #141c2e; -fx-padding: 10 14; -fx-background-radius: 8; -fx-border-color: #1e293b; -fx-border-radius: 8;");
        return box;
    }

    private void runReflectionDemo() {
        List<TaskDefinition> tasks = context.getAllTaskDefinitions();
        StringBuilder sb = new StringBuilder();
        for (TaskDefinition t : tasks) {
            Class<?> c = t.getPlugin().getClass();
            Method[] methods = c.getDeclaredMethods();
            sb.append(t.getName()).append(" -> ").append(methods.length).append(" methods reflectively inspected. ");
        }
        lblObservedReflection.setText(sb.toString());
    }

    private void updateLiveObservations() {
        int pluginCount = context.getPluginManager().getLoadedPlugins().size();
        lblObservedClassLoading.setText(String.format("Observed: %d dynamic plugin(s) active in isolated URLClassLoader(s).", pluginCount));
        lblObservedConcurrency.setText(String.format("Observed: %d active worker thread(s) in ThreadPoolExecutor.", context.getTaskEngine().getActiveTaskCount()));
    }

    @Override
    public void onMetricsSampled(JvmMetricsSnapshot snapshot) {
        Platform.runLater(() -> {
            if (lblObservedGc != null && !lblObservedGc.getText().startsWith("Allocated") && !lblObservedGc.getText().startsWith("References")) {
                lblObservedGc.setText(String.format(Locale.ROOT,
                        "Heap: %.1f MB used, Total GC Collections: %d (%d ms)",
                        snapshot.getHeapUsedMb(), snapshot.totalGcCount(), snapshot.totalGcTimeMs()));
            }
        });
    }
}

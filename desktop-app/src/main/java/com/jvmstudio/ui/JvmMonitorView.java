package com.jvmstudio.ui;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.jvm.JvmMonitorService;
import com.jvmstudio.core.model.JvmMetricsSnapshot;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.chart.AreaChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real-time JVM monitoring view. Visualizes heap vs non-heap allocations,
 * live thread metrics, garbage collection occurrences, and JVM portability information.
 */
public class JvmMonitorView extends ScrollPane implements JvmMonitorService.MetricsListener {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AppContext context;

    private Label lblHeapUsed;
    private Label lblHeapCommitted;
    private Label lblHeapMax;
    private Label lblNonHeapUsed;
    private Label lblLiveThreads;
    private Label lblPeakThreads;
    private Label lblGcCount;
    private Label lblGcTime;

    private XYChart.Series<String, Number> heapUsedSeries;
    private XYChart.Series<String, Number> heapCommittedSeries;
    private XYChart.Series<String, Number> threadSeries;

    private TableView<JvmMonitorService.GcCollectorInfo> gcTable;

    // Interactive memory buffer holder for the quick demo
    private final List<byte[]> quickAllocations = new ArrayList<>();
    private Label lblQuickAllocStatus;

    public JvmMonitorView(AppContext context) {
        this.context = context;

        setFitToWidth(true);
        getStyleClass().add("scroll-pane");

        VBox root = new VBox(20);
        root.getStyleClass().add("content-area");
        root.setPadding(new Insets(24));

        root.getChildren().addAll(
                buildHeaderSection(),
                buildMetricsTilesRow(),
                buildChartsRow(),
                buildGcAndRuntimeSection(),
                buildInteractiveMemoryDemoCard()
        );

        setContent(root);

        // Pre-fill history in chart
        loadHistoricalData();

        context.getJvmMonitorService().addListener(this);
    }

    private Node buildHeaderSection() {
        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox titleBox = new VBox(4);
        Label title = new Label("JVM Runtime & Garbage Collection Monitor");
        title.getStyleClass().add("card-title");
        title.setStyle("-fx-font-size: 20px;");

        Label sub = new Label("Observing actual JVM Management Beans: MemoryMXBean, ThreadMXBean, and GarbageCollectorMXBeans.");
        sub.getStyleClass().add("card-subtitle");
        titleBox.getChildren().addAll(title, sub);

        Button btnGc = UiHelper.createWarningButton("Request GC (System.gc())", this::triggerGcRequest);

        header.getChildren().addAll(titleBox, UiHelper.createSpacer(), btnGc);
        return header;
    }

    private Node buildMetricsTilesRow() {
        HBox row = new HBox(14);
        row.setAlignment(Pos.CENTER);

        VBox t1 = UiHelper.createMetricTile("Heap Used", "0 MB", "Current heap space occupied");
        lblHeapUsed = (Label) t1.lookup("#val-heap-used");

        VBox t2 = UiHelper.createMetricTile("Heap Committed", "0 MB", "OS virtual memory reserved for heap");
        lblHeapCommitted = (Label) t2.lookup("#val-heap-committed");

        VBox t3 = UiHelper.createMetricTile("Heap Max", "0 MB", "-Xmx ceiling defined by JVM");
        lblHeapMax = (Label) t3.lookup("#val-heap-max");

        VBox t4 = UiHelper.createMetricTile("Non-Heap / Metaspace", "0 MB", "Class metadata & JIT compiler memory");
        lblNonHeapUsed = (Label) t4.lookup("#val-non-heap-/-metaspace");

        VBox t5 = UiHelper.createMetricTile("Live Threads", "0", "Platform and worker threads");
        lblLiveThreads = (Label) t5.lookup("#val-live-threads");

        row.getChildren().addAll(t1, t2, t3, t4, t5);
        return row;
    }

    private Node buildChartsRow() {
        HBox row = new HBox(16);

        // Heap Chart
        VBox heapCard = new VBox(10);
        heapCard.getStyleClass().add("card");
        HBox.setHgrow(heapCard, Priority.ALWAYS);

        heapCard.getChildren().add(UiHelper.createTitle("Heap Memory Usage (MB)"));

        CategoryAxis xAxis = new CategoryAxis();
        xAxis.setAnimated(false);
        NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Megabytes (MB)");
        yAxis.setAnimated(false);

        AreaChart<String, Number> heapChart = new AreaChart<>(xAxis, yAxis);
        heapChart.setAnimated(false);
        heapChart.setPrefHeight(260);

        heapUsedSeries = new XYChart.Series<>();
        heapUsedSeries.setName("Heap Used");

        heapCommittedSeries = new XYChart.Series<>();
        heapCommittedSeries.setName("Heap Committed");

        heapChart.getData().addAll(heapUsedSeries, heapCommittedSeries);
        heapCard.getChildren().add(heapChart);

        // Threads Chart
        VBox threadCard = new VBox(10);
        threadCard.getStyleClass().add("card");
        HBox.setHgrow(threadCard, Priority.ALWAYS);

        threadCard.getChildren().add(UiHelper.createTitle("Live Thread Count"));

        CategoryAxis txAxis = new CategoryAxis();
        txAxis.setAnimated(false);
        NumberAxis tyAxis = new NumberAxis();
        tyAxis.setLabel("Active Threads");
        tyAxis.setAnimated(false);

        LineChart<String, Number> threadChart = new LineChart<>(txAxis, tyAxis);
        threadChart.setAnimated(false);
        threadChart.setPrefHeight(260);

        threadSeries = new XYChart.Series<>();
        threadSeries.setName("Live Threads");

        threadChart.getData().add(threadSeries);
        threadCard.getChildren().add(threadChart);

        row.getChildren().addAll(heapCard, threadCard);
        return row;
    }

    private Node buildGcAndRuntimeSection() {
        HBox row = new HBox(16);

        // GC Table Card
        VBox gcCard = new VBox(10);
        gcCard.getStyleClass().add("card");
        HBox.setHgrow(gcCard, Priority.ALWAYS);

        gcCard.getChildren().addAll(
                UiHelper.createTitle("Garbage Collector Telemetry"),
                UiHelper.createSubtitle("Cumulative metrics directly from GarbageCollectorMXBean instances.")
        );

        gcTable = new TableView<>();
        gcTable.setPrefHeight(150);

        TableColumn<JvmMonitorService.GcCollectorInfo, String> nameCol = new TableColumn<>("Collector Name");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameCol.setPrefWidth(160);

        TableColumn<JvmMonitorService.GcCollectorInfo, Long> countCol = new TableColumn<>("Collections");
        countCol.setCellValueFactory(new PropertyValueFactory<>("collectionCount"));
        countCol.setPrefWidth(120);

        TableColumn<JvmMonitorService.GcCollectorInfo, Long> timeCol = new TableColumn<>("Total Time (ms)");
        timeCol.setCellValueFactory(new PropertyValueFactory<>("collectionTimeMs"));
        timeCol.setPrefWidth(140);

        gcTable.getColumns().addAll(nameCol, countCol, timeCol);
        gcCard.getChildren().add(gcTable);

        // Runtime & Portability Card
        VBox runtimeCard = new VBox(10);
        runtimeCard.getStyleClass().add("card");
        HBox.setHgrow(runtimeCard, Priority.ALWAYS);

        runtimeCard.getChildren().addAll(
                UiHelper.createTitle("JVM Runtime & Portability Specifications"),
                UiHelper.createSubtitle("Platform environment details queried from Runtime and OperatingSystemMXBean.")
        );

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);

        JvmMonitorService jvm = context.getJvmMonitorService();
        addRuntimeRow(grid, 0, "Java Version:", jvm.getJavaVersion());
        addRuntimeRow(grid, 1, "JVM Specification:", jvm.getJvmName());
        addRuntimeRow(grid, 2, "Operating System:", jvm.getOsInfo());
        addRuntimeRow(grid, 3, "Available Processors:", String.valueOf(Runtime.getRuntime().availableProcessors()));
        addRuntimeRow(grid, 4, "Process Architecture:", System.getProperty("os.arch"));
        addRuntimeRow(grid, 5, "Bytecode Standard:", "Java Class File Format 65.0 (Java 21)");

        runtimeCard.getChildren().add(grid);

        row.getChildren().addAll(gcCard, runtimeCard);
        return row;
    }

    private void addRuntimeRow(GridPane grid, int row, String label, String value) {
        Label l = new Label(label);
        l.setStyle("-fx-font-weight: 600; -fx-text-fill: #94a3b8;");
        Label v = new Label(value);
        v.setStyle("-fx-text-fill: #f8fafc; -fx-font-family: monospace;");
        grid.add(l, 0, row);
        grid.add(v, 1, row);
    }

    private Node buildInteractiveMemoryDemoCard() {
        VBox card = new VBox(12);
        card.getStyleClass().add("card");

        card.getChildren().addAll(
                UiHelper.createTitle("Interactive Heap Memory & GC Learning Lab Controls"),
                UiHelper.createSubtitle(
                        "Allocate real 20MB chunks in heap memory, observe how references anchor objects in Eden/Survivor/Old generation, " +
                        "and release references to make them eligible for garbage collection."
                )
        );

        HBox controls = new HBox(12);
        controls.setAlignment(Pos.CENTER_LEFT);

        Button btnAlloc = UiHelper.createPrimaryButton("+ Allocate 20 MB Chunk", () -> {
            try {
                byte[] chunk = new byte[20 * 1024 * 1024];
                chunk[0] = 42;
                chunk[chunk.length - 1] = 42;
                synchronized (quickAllocations) {
                    quickAllocations.add(chunk);
                }
                updateQuickAllocStatus();
            } catch (OutOfMemoryError oom) {
                UiHelper.showError("Heap Bound Exceeded", "Cannot allocate chunk", "Safe limit reached. Please free references.");
            }
        });

        Button btnFree = UiHelper.createSecondaryButton("Release References", () -> {
            synchronized (quickAllocations) {
                quickAllocations.clear();
            }
            updateQuickAllocStatus();
        });

        Button btnGc = UiHelper.createWarningButton("Request GC (System.gc())", this::triggerGcRequest);

        lblQuickAllocStatus = new Label("Allocated chunks: 0 (0 MB referenced)");
        lblQuickAllocStatus.setStyle("-fx-font-weight: 600; -fx-text-fill: #cbd5e1;");

        controls.getChildren().addAll(btnAlloc, btnFree, btnGc, UiHelper.createSpacer(), lblQuickAllocStatus);

        Label disclaimer = new Label(
                "Technical Note: Releasing references removes GC roots. The objects become unreachable, but physical heap deallocation occurs " +
                "only when the garbage collector runs. System.gc() is a request, not a guarantee."
        );
        disclaimer.setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;");
        disclaimer.setWrapText(true);

        card.getChildren().addAll(controls, disclaimer);
        return card;
    }

    private void updateQuickAllocStatus() {
        int count;
        synchronized (quickAllocations) {
            count = quickAllocations.size();
        }
        lblQuickAllocStatus.setText(String.format(Locale.ROOT, "Allocated chunks: %d (%d MB referenced)", count, count * 20));
    }

    private void triggerGcRequest() {
        UiHelper.runAsync(() -> {
            System.gc();
            Platform.runLater(() -> {
                UiHelper.showInfo("Garbage Collection Requested", "System.gc() Dispatched",
                        "A request has been sent to the JVM Garbage Collector.\n" +
                        "Observe the Heap chart and GC collector table for incremental collection metrics.");
            });
        }, null);
    }

    private void loadHistoricalData() {
        List<JvmMetricsSnapshot> history = context.getJvmMonitorService().getHistory();
        for (JvmMetricsSnapshot s : history) {
            String time = TIME_FMT.format(LocalTime.now());
            heapUsedSeries.getData().add(new XYChart.Data<>(time, s.getHeapUsedMb()));
            heapCommittedSeries.getData().add(new XYChart.Data<>(time, s.getHeapCommittedMb()));
            threadSeries.getData().add(new XYChart.Data<>(time, s.liveThreadCount()));
        }
    }

    @Override
    public void onMetricsSampled(JvmMetricsSnapshot snapshot) {
        Platform.runLater(() -> {
            if (lblHeapUsed != null) {
                lblHeapUsed.setText(String.format(Locale.ROOT, "%.1f MB", snapshot.getHeapUsedMb()));
                lblHeapCommitted.setText(String.format(Locale.ROOT, "%.1f MB", snapshot.getHeapCommittedMb()));
                lblHeapMax.setText(snapshot.getHeapMaxMb() > 0 ? String.format(Locale.ROOT, "%.1f MB", snapshot.getHeapMaxMb()) : "Undefined");
                lblNonHeapUsed.setText(String.format(Locale.ROOT, "%.1f MB", snapshot.getNonHeapUsedMb()));
                lblLiveThreads.setText(String.valueOf(snapshot.liveThreadCount()));
            }

            // Update charts
            String time = TIME_FMT.format(LocalTime.now());

            if (heapUsedSeries.getData().size() > 40) {
                heapUsedSeries.getData().remove(0);
            }
            heapUsedSeries.getData().add(new XYChart.Data<>(time, snapshot.getHeapUsedMb()));

            if (heapCommittedSeries.getData().size() > 40) {
                heapCommittedSeries.getData().remove(0);
            }
            heapCommittedSeries.getData().add(new XYChart.Data<>(time, snapshot.getHeapCommittedMb()));

            if (threadSeries.getData().size() > 40) {
                threadSeries.getData().remove(0);
            }
            threadSeries.getData().add(new XYChart.Data<>(time, snapshot.liveThreadCount()));

            // Update GC Table
            if (gcTable != null) {
                gcTable.getItems().setAll(context.getJvmMonitorService().getGcCollectorDetails());
            }
        });
    }
}

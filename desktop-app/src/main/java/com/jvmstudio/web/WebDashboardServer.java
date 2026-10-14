package com.jvmstudio.web;

import com.jvmstudio.core.AppContext;
import com.jvmstudio.core.engine.TaskHandle;
import com.jvmstudio.core.jvm.JvmMonitorService;
import com.jvmstudio.core.model.JvmMetricsSnapshot;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskState;
import com.jvmstudio.core.plugins.PluginManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP server powering the live Browser Dashboard of JVM Studio.
 * Exposes REST APIs and serves a real-time reactive dark web interface.
 */
public class WebDashboardServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(WebDashboardServer.class);

    private final AppContext context;
    private final int port;
    private HttpServer server;

    private final List<byte[]> webMemoryHolder = Collections.synchronizedList(new ArrayList<>());

    public WebDashboardServer(AppContext context, int port) {
        this.context = context;
        this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        server.createContext("/", this::handleRoot);
        server.createContext("/api/metrics", this::handleMetrics);
        server.createContext("/api/tasks", this::handleTasks);
        server.createContext("/api/tasks/run", this::handleTaskRun);
        server.createContext("/api/tasks/pause", this::handleTaskPause);
        server.createContext("/api/tasks/resume", this::handleTaskResume);
        server.createContext("/api/tasks/cancel", this::handleTaskCancel);
        server.createContext("/api/history", this::handleHistory);
        server.createContext("/api/gc", this::handleGc);
        server.createContext("/api/alloc", this::handleAlloc);
        server.createContext("/api/free", this::handleFree);
        server.createContext("/api/plugins/load-sample", this::handleLoadSamplePlugin);

        server.start();
        logger.info("JVM Studio Web Dashboard is live at http://localhost:{}", port);
    }

    public int getPort() {
        return port;
    }

    private void handleRoot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String html = getDashboardHtml();
        sendResponse(exchange, 200, "text/html; charset=UTF-8", html);
    }

    private void handleMetrics(HttpExchange exchange) throws IOException {
        JvmMetricsSnapshot s = context.getJvmMonitorService().getLatestSnapshot();
        if (s == null) {
            sendResponse(exchange, 200, "application/json", "{}");
            return;
        }

        String json = String.format(Locale.ROOT, """
            {
              "heapUsedMb": %.2f,
              "heapCommittedMb": %.2f,
              "heapMaxMb": %.2f,
              "nonHeapUsedMb": %.2f,
              "liveThreads": %d,
              "peakThreads": %d,
              "totalGcCount": %d,
              "totalGcTimeMs": %d,
              "gcCountDelta": %d,
              "gcTimeMsDelta": %d,
              "availableProcessors": %d,
              "javaVersion": "%s",
              "jvmName": "%s",
              "osInfo": "%s",
              "activeTasks": %d,
              "uptimeSeconds": %d,
              "webAllocatedMb": %d
            }
            """,
                s.getHeapUsedMb(),
                s.getHeapCommittedMb(),
                s.getHeapMaxMb(),
                s.getNonHeapUsedMb(),
                s.liveThreadCount(),
                s.peakThreadCount(),
                s.totalGcCount(),
                s.totalGcTimeMs(),
                s.gcCountDelta(),
                s.gcTimeMsDelta(),
                s.availableProcessors(),
                escapeJson(context.getJvmMonitorService().getJavaVersion()),
                escapeJson(context.getJvmMonitorService().getJvmName()),
                escapeJson(context.getJvmMonitorService().getOsInfo()),
                context.getTaskEngine().getActiveTaskCount(),
                context.getJvmMonitorService().getUptimeMs() / 1000,
                webMemoryHolder.size() * 20
        );
        sendResponse(exchange, 200, "application/json", json);
    }

    private void handleTasks(HttpExchange exchange) throws IOException {
        List<TaskDefinition> tasks = context.getAllTaskDefinitions();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < tasks.size(); i++) {
            TaskDefinition t = tasks.get(i);
            TaskHandle handle = context.getTaskEngine().getActiveTaskHandle(t.getId());
            String state = handle != null ? handle.getState().name() : "READY";
            double progress = handle != null ? handle.getProgress() : 0.0;
            String msg = handle != null ? handle.getMessage() : "Ready to execute.";

            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT, """
                {
                  "id": "%s",
                  "name": "%s",
                  "version": "%s",
                  "author": "%s",
                  "category": "%s",
                  "description": "%s",
                  "isBuiltIn": %b,
                  "state": "%s",
                  "progress": %.2f,
                  "message": "%s"
                }
                """,
                    escapeJson(t.getId()),
                    escapeJson(t.getName()),
                    escapeJson(t.getVersion()),
                    escapeJson(t.getAuthor()),
                    escapeJson(t.getCategory()),
                    escapeJson(t.getDescription()),
                    t.isBuiltIn(),
                    state,
                    progress,
                    escapeJson(msg)
            ));
        }
        sb.append("]");
        sendResponse(exchange, 200, "application/json", sb.toString());
    }

    private void handleTaskRun(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String id = query.get("id");
        TaskDefinition def = context.getTaskDefinition(id);
        if (def == null) {
            sendResponse(exchange, 404, "application/json", "{\"error\":\"Task not found\"}");
            return;
        }

        // Run with default/current parameters
        String execId = context.getTaskEngine().runTask(def, null);
        if (execId == null) {
            sendResponse(exchange, 409, "application/json", "{\"status\":\"skipped\",\"message\":\"Task already running (Overlap Policy)\"}");
        } else {
            sendResponse(exchange, 200, "application/json", "{\"status\":\"started\",\"executionId\":\"" + execId + "\"}");
        }
    }

    private void handleTaskPause(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String id = query.get("id");
        boolean ok = context.getTaskEngine().pauseTask(id);
        sendResponse(exchange, 200, "application/json", "{\"success\":" + ok + "}");
    }

    private void handleTaskResume(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String id = query.get("id");
        boolean ok = context.getTaskEngine().resumeTask(id);
        sendResponse(exchange, 200, "application/json", "{\"success\":" + ok + "}");
    }

    private void handleTaskCancel(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
        String id = query.get("id");
        boolean ok = context.getTaskEngine().cancelTask(id);
        sendResponse(exchange, 200, "application/json", "{\"success\":" + ok + "}");
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        List<TaskHistoryRecord> records = context.getHistoryRepository().findAllRecent(50);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < records.size(); i++) {
            TaskHistoryRecord r = records.get(i);
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.ROOT, """
                {
                  "executionId": "%s",
                  "taskId": "%s",
                  "taskName": "%s",
                  "category": "%s",
                  "state": "%s",
                  "durationMs": %d,
                  "itemsProcessed": %d,
                  "summary": "%s"
                }
                """,
                    escapeJson(r.executionId()),
                    escapeJson(r.taskId()),
                    escapeJson(r.taskName()),
                    escapeJson(r.category()),
                    r.state().name(),
                    r.durationMs(),
                    r.itemsProcessed(),
                    escapeJson(r.summary() != null ? r.summary() : "")
            ));
        }
        sb.append("]");
        sendResponse(exchange, 200, "application/json", sb.toString());
    }

    private void handleGc(HttpExchange exchange) throws IOException {
        System.gc();
        sendResponse(exchange, 200, "application/json", "{\"status\":\"GC requested\",\"notice\":\"System.gc() dispatched to JVM HotSpot\"}");
    }

    private void handleAlloc(HttpExchange exchange) throws IOException {
        try {
            byte[] chunk = new byte[20 * 1024 * 1024];
            chunk[0] = 7;
            chunk[chunk.length - 1] = 7;
            webMemoryHolder.add(chunk);
            sendResponse(exchange, 200, "application/json", "{\"status\":\"allocated\",\"chunks\":" + webMemoryHolder.size() + ",\"totalMb\":" + (webMemoryHolder.size() * 20) + "}");
        } catch (OutOfMemoryError oom) {
            sendResponse(exchange, 500, "application/json", "{\"error\":\"OutOfMemoryError prevented: heap limit reached\"}");
        }
    }

    private void handleFree(HttpExchange exchange) throws IOException {
        webMemoryHolder.clear();
        sendResponse(exchange, 200, "application/json", "{\"status\":\"freed\",\"chunks\":0}");
    }

    private void handleLoadSamplePlugin(HttpExchange exchange) throws IOException {
        try {
            Path jar = Path.of("sample-plugin/target/sample-plugin-1.0.0.jar").toAbsolutePath();
            if (!java.nio.file.Files.exists(jar)) {
                sendResponse(exchange, 404, "application/json", "{\"error\":\"sample-plugin JAR not found at " + escapeJson(jar.toString()) + "\"}");
                return;
            }
            TaskDefinition def = context.getPluginManager().loadPluginJar(jar);
            sendResponse(exchange, 200, "application/json", "{\"status\":\"loaded\",\"id\":\"" + def.getId() + "\",\"name\":\"" + escapeJson(def.getName()) + "\"}");
        } catch (Exception e) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private void sendResponse(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(String query) {
        if (query == null || query.isBlank()) return Collections.emptyMap();
        Map<String, String> map = new HashMap<>();
        for (String param : query.split("&")) {
            String[] pair = param.split("=");
            if (pair.length > 1) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
            } else if (pair.length == 1) {
                map.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), "");
            }
        }
        return map;
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(1);
            logger.info("Web Dashboard stopped.");
        }
    }

    private String getDashboardHtml() {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>JVM Studio — Live Browser Dashboard</title>
  <style>
    :root {
      --bg-dark: #070a12;
      --bg-surface: #0d121f;
      --bg-card: #131b2e;
      --bg-tile: #111827;
      --border: #243048;
      --primary: #3b82f6;
      --primary-hover: #2563eb;
      --success: #10b981;
      --warning: #f59e0b;
      --danger: #ef4444;
      --purple: #8b5cf6;
      --text-main: #f8fafc;
      --text-muted: #94a3b8;
      --text-faint: #64748b;
    }
    * { box-sizing: border-box; margin: 0; padding: 0; }
    body {
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      background: var(--bg-dark);
      color: var(--text-main);
      display: flex;
      min-height: 100vh;
    }
    /* Sidebar */
    .sidebar {
      width: 250px;
      background: var(--bg-surface);
      border-right: 1px solid var(--border);
      padding: 24px 16px;
      display: flex;
      flex-direction: column;
      gap: 20px;
    }
    .brand-title {
      font-size: 20px;
      font-weight: 800;
      color: #fff;
      letter-spacing: -0.5px;
    }
    .brand-sub {
      font-size: 10px;
      color: var(--text-faint);
      font-weight: 700;
      letter-spacing: 1px;
    }
    .nav-list { display: flex; flex-direction: column; gap: 6px; }
    .nav-item {
      padding: 10px 14px;
      border-radius: 8px;
      font-size: 13px;
      color: var(--text-muted);
      cursor: pointer;
      font-weight: 500;
      transition: all 0.15s;
    }
    .nav-item:hover { background: #162032; color: #fff; }
    .nav-item.active { background: rgba(59, 130, 246, 0.18); color: #60a5fa; font-weight: 700; border-left: 3px solid var(--primary); }
    .sidebar-footer {
      margin-top: auto;
      background: #080c16;
      padding: 12px;
      border-radius: 8px;
      border: 1px solid #1a2234;
      font-size: 11px;
    }
    /* Main Area */
    .main { flex: 1; display: flex; flex-direction: column; overflow-y: auto; }
    .header {
      background: var(--bg-surface);
      border-bottom: 1px solid var(--border);
      padding: 16px 28px;
      display: flex;
      align-items: center;
      justify-content: space-between;
    }
    .header-title { font-size: 20px; font-weight: 700; }
    .btn {
      padding: 8px 16px;
      border-radius: 6px;
      font-size: 12px;
      font-weight: 600;
      cursor: pointer;
      border: none;
      transition: all 0.15s;
    }
    .btn-primary { background: var(--primary); color: #fff; }
    .btn-primary:hover { background: var(--primary-hover); }
    .btn-warning { background: var(--warning); color: #000; }
    .btn-warning:hover { background: #d97706; }
    .btn-danger { background: var(--danger); color: #fff; }
    .btn-danger:hover { background: #dc2626; }
    .btn-secondary { background: #1e293b; color: var(--text-main); border: 1px solid var(--border); }
    .btn-secondary:hover { background: #27354f; }
    .content { padding: 28px; display: flex; flex-direction: column; gap: 24px; }
    /* Metric Tiles */
    .metric-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; }
    .metric-tile {
      background: var(--bg-tile);
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .metric-label { font-size: 11px; font-weight: 600; color: var(--text-faint); text-transform: uppercase; }
    .metric-value { font-size: 26px; font-weight: 800; color: #fff; }
    .metric-sub { font-size: 11px; color: var(--text-muted); }
    /* Cards */
    .card {
      background: var(--bg-card);
      border: 1px solid var(--border);
      border-radius: 12px;
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .card-title { font-size: 16px; font-weight: 700; color: #fff; }
    .card-subtitle { font-size: 12px; color: var(--text-muted); }
    /* Task Cards */
    .tasks-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(360px, 1fr)); gap: 18px; }
    .task-card {
      background: #0f1626;
      border: 1px solid var(--border);
      border-radius: 10px;
      padding: 18px;
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .task-header { display: flex; align-items: center; justify-content: space-between; }
    .badge {
      padding: 3px 10px;
      border-radius: 12px;
      font-size: 11px;
      font-weight: 700;
    }
    .badge-ready { background: rgba(148, 163, 184, 0.15); color: #cbd5e1; }
    .badge-running { background: rgba(59, 130, 246, 0.2); color: #60a5fa; }
    .badge-completed { background: rgba(16, 185, 129, 0.2); color: #34d399; }
    .badge-paused { background: rgba(245, 158, 11, 0.2); color: #fbbf24; }
    .badge-failed { background: rgba(239, 68, 68, 0.2); color: #f87171; }
    /* Progress bar */
    .progress-bar-bg {
      background: #0e1524;
      height: 8px;
      border-radius: 4px;
      overflow: hidden;
      margin-top: 4px;
    }
    .progress-bar-fill {
      background: linear-gradient(to right, #3b82f6, #60a5fa);
      height: 100%;
      width: 0%;
      transition: width 0.3s;
    }
    /* Tables */
    table { width: 100%; border-collapse: collapse; font-size: 12px; }
    th { text-align: left; padding: 10px 12px; background: #141d30; color: var(--text-muted); font-weight: 600; text-transform: uppercase; font-size: 10px; }
    td { padding: 10px 12px; border-bottom: 1px solid #162032; }
    tr:hover { background: #1c273e; }
    /* Heap Meter */
    .meter-container { display: flex; flex-direction: column; gap: 8px; }
    .meter-bar { height: 20px; background: #1e293b; border-radius: 6px; overflow: hidden; display: flex; }
    .meter-used { background: #3b82f6; height: 100%; width: 25%; transition: width 0.4s; }
    .meter-committed { background: rgba(59, 130, 246, 0.25); height: 100%; width: 50%; }
    .screen-section { display: none; flex-direction: column; gap: 20px; }
    .screen-section.active { display: flex; }
  </style>
</head>
<body>
  <div class="sidebar">
    <div>
      <div class="brand-title">JVM Studio</div>
      <div class="brand-sub">LIVE BROWSER DASHBOARD</div>
    </div>
    <div class="nav-list">
      <div class="nav-item active" onclick="switchTab('overview')">Overview</div>
      <div class="nav-item" onclick="switchTab('tasks')">Tasks & Orchestration</div>
      <div class="nav-item" onclick="switchTab('plugins')">Dynamic Plugins</div>
      <div class="nav-item" onclick="switchTab('monitor')">JVM Monitor & GC</div>
      <div class="nav-item" onclick="switchTab('history')">Execution History</div>
      <div class="nav-item" onclick="switchTab('lab')">Java vs C++ Lab</div>
      <div class="nav-item" onclick="switchTab('settings')">Settings</div>
    </div>
    <div class="sidebar-footer">
      <div id="footer-java" style="color:#60a5fa; font-weight:bold;">Java 21 LTS</div>
      <div id="footer-os" style="color:#64748b; font-size:10px;">HotSpot 64-Bit VM</div>
    </div>
  </div>

  <div class="main">
    <div class="header">
      <div class="header-title" id="page-title">System Overview</div>
      <div style="display:flex; gap:10px; align-items:center;">
        <span id="header-status" style="font-size:11px; background:#162032; padding:4px 10px; border-radius:12px; color:#94a3b8;">Engine: Ready</span>
        <button class="btn btn-warning" onclick="requestGc()">Request GC (System.gc())</button>
      </div>
    </div>

    <div class="content">
      <!-- 1. OVERVIEW SCREEN -->
      <div id="tab-overview" class="screen-section active">
        <div class="metric-row">
          <div class="metric-tile">
            <div class="metric-label">Active Workers</div>
            <div class="metric-value" id="val-workers">0</div>
            <div class="metric-sub">Concurrent background tasks</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Heap Memory Used</div>
            <div class="metric-value" id="val-heap">0 MB</div>
            <div class="metric-sub" id="val-heap-sub">0% of committed</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Live Threads</div>
            <div class="metric-value" id="val-threads">0</div>
            <div class="metric-sub">JVM platform & worker threads</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">GC Collections</div>
            <div class="metric-value" id="val-gc">0</div>
            <div class="metric-sub" id="val-gc-time">0 ms total elapsed</div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">JVM Heap Visualizer (Used vs Committed vs Max)</div>
          <div class="meter-container">
            <div class="meter-bar">
              <div id="meter-used-bar" class="meter-used"></div>
              <div id="meter-comm-bar" class="meter-committed"></div>
            </div>
            <div style="display:flex; justify-content:space-between; font-size:11px; color:var(--text-muted);">
              <span id="meter-used-text">Used: 0 MB</span>
              <span id="meter-comm-text">Committed: 0 MB</span>
              <span id="meter-max-text">Max (-Xmx): 0 MB</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div class="card-title">Registered Tasks & Execution Hub</div>
              <div class="card-subtitle">Trigger concurrent tasks in background worker threads without UI freezing.</div>
            </div>
            <button class="btn btn-secondary" onclick="switchTab('tasks')">View Full Tasks View</button>
          </div>
          <div class="tasks-grid" id="quick-tasks-container"></div>
        </div>
      </div>

      <!-- 2. TASKS SCREEN -->
      <div id="tab-tasks" class="screen-section">
        <div class="card">
          <div class="card-title">Task Orchestration Cards</div>
          <div class="card-subtitle">Execute built-in file operations, memory diagnostics, and loaded plugins with cooperative pause/resume/cancel.</div>
          <div class="tasks-grid" id="all-tasks-container"></div>
        </div>
      </div>

      <!-- 3. PLUGINS SCREEN -->
      <div id="tab-plugins" class="screen-section">
        <div class="card" style="background:#1a1523; border-color:#4c1d95;">
          <div style="font-size:12px; font-weight:bold; color:#a78bfa;">SECURITY ARCHITECTURE NOTICE</div>
          <div style="font-size:12px; color:#cbd5e1; line-height:1.4;">
            Plugins execute arbitrary Java bytecode within the host process with standard user permissions.
            URLClassLoader provides modular class isolation, NOT a sandboxed VM.
          </div>
          <button class="btn btn-primary" style="width:fit-content;" onclick="loadSamplePlugin()">Load Sample Plugin JAR (Word Frequency Counter)</button>
        </div>
        <div class="card">
          <div class="card-title">Active Dynamic Plugins</div>
          <div id="plugins-list" style="font-size:13px; color:var(--text-muted);">Click button above to load sample-plugin-1.0.0.jar dynamically.</div>
        </div>
      </div>

      <!-- 4. JVM MONITOR SCREEN -->
      <div id="tab-monitor" class="screen-section">
        <div class="card">
          <div class="card-title">Interactive Heap Allocation & GC Observation Lab</div>
          <div class="card-subtitle">Allocate real 20 MB chunks in JVM memory, observe how references anchor objects in the heap, and release references.</div>
          <div style="display:flex; gap:12px; align-items:center; margin-top:8px;">
            <button class="btn btn-primary" onclick="allocChunk()">+ Allocate 20 MB Chunk</button>
            <button class="btn btn-secondary" onclick="freeChunks()">Release References</button>
            <button class="btn btn-warning" onclick="requestGc()">Request GC (System.gc())</button>
            <span id="alloc-status" style="font-weight:600; color:#38bdf8;">Allocated: 0 MB</span>
          </div>
        </div>
        <div class="metric-row">
          <div class="metric-tile">
            <div class="metric-label">Non-Heap (Metaspace)</div>
            <div class="metric-value" id="val-nonheap">0 MB</div>
            <div class="metric-sub">Class metadata & JIT buffers</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Peak Threads</div>
            <div class="metric-value" id="val-peak-threads">0</div>
            <div class="metric-sub">Historical thread peak</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">CPU Cores</div>
            <div class="metric-value" id="val-cores">0</div>
            <div class="metric-sub">Available hardware threads</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Process Uptime</div>
            <div class="metric-value" id="val-uptime">0s</div>
            <div class="metric-sub">Running duration</div>
          </div>
        </div>
      </div>

      <!-- 5. HISTORY SCREEN -->
      <div id="tab-history" class="screen-section">
        <div class="card">
          <div class="card-title">Execution History (Persisted in SQLite)</div>
          <div class="card-subtitle">Audit log of all completed, cancelled, and failed task executions.</div>
          <table>
            <thead>
              <tr>
                <th>Exec ID</th>
                <th>Task Name</th>
                <th>Category</th>
                <th>State</th>
                <th>Duration (ms)</th>
                <th>Items</th>
                <th>Summary</th>
              </tr>
            </thead>
            <tbody id="history-rows"></tbody>
          </table>
        </div>
      </div>

      <!-- 6. LEARNING LAB SCREEN -->
      <div id="tab-lab" class="screen-section">
        <div class="card">
          <div class="card-title">1. Dynamic Class Loading vs C++ dlopen</div>
          <div class="card-subtitle">
            <strong>Java:</strong> Hierarchical ClassLoader loads bytecode into Metaspace at runtime; isolates modules.<br>
            <strong>C++:</strong> Uses dlopen/LoadLibrary; lacks standard ABI across compilers (name mangling requires extern "C").
          </div>
        </div>
        <div class="card">
          <div class="card-title">2. Reflection & Metadata vs C++ RTTI</div>
          <div class="card-subtitle">
            <strong>Java:</strong> Runtime-retained annotations (@TaskInfo) and java.lang.reflect allow discovering methods and fields dynamically.<br>
            <strong>C++:</strong> Standard C++ provides basic typeid/dynamic_cast but no standard language-level method enumeration.
          </div>
        </div>
        <div class="card">
          <div class="card-title">3. Garbage Collection vs Deterministic RAII</div>
          <div class="card-subtitle">
            <strong>Java:</strong> Automatic generational GC prevents use-after-free and double-free, but does NOT replace closing OS files (AutoCloseable needed).<br>
            <strong>C++:</strong> Destructors execute deterministically at scope exit; instant resource cleanup with zero GC pause overhead.
          </div>
        </div>
        <div class="card">
          <div class="card-title">4. Bytecode Portability vs Native Machine Code</div>
          <div class="card-subtitle">
            <strong>Java:</strong> Standard bytecode runs on any compatible JVM; HotSpot JIT compiles hot paths to native machine assembly.<br>
            <strong>C++:</strong> Directly compiles to native CPU instructions (x86_64, ARM64); zero VM overhead but requires separate native binaries per OS.
          </div>
        </div>
      </div>

      <!-- 7. SETTINGS SCREEN -->
      <div id="tab-settings" class="screen-section">
        <div class="card">
          <div class="card-title">System Settings & Concurrency Engine</div>
          <div class="card-subtitle">Configured through SQLite database (~/.jvmstudio/jvmstudio.db).</div>
          <div style="font-size:13px; line-height:1.8; color:#cbd5e1;">
            <div><strong>Persistence Engine:</strong> SQLite 3 via WAL Journal Mode</div>
            <div><strong>Worker Thread Pool:</strong> Configured bounded ThreadPoolExecutor</div>
            <div><strong>Telemetry Polling Rate:</strong> 1000 ms</div>
            <div><strong>Default Collision Policy:</strong> Rename with timestamp suffix</div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <script>
    function switchTab(name) {
      document.querySelectorAll('.screen-section').forEach(s => s.classList.remove('active'));
      document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
      document.getElementById('tab-' + name).classList.add('active');
      event.target.classList.add('active');
      document.getElementById('page-title').innerText = name.toUpperCase() + ' DASHBOARD';
      if (name === 'history') loadHistory();
      if (name === 'tasks') loadTasks();
    }

    async function pollMetrics() {
      try {
        const res = await fetch('/api/metrics');
        const d = await res.json();
        document.getElementById('val-workers').innerText = d.activeTasks;
        document.getElementById('val-heap').innerText = d.heapUsedMb + ' MB';
        document.getElementById('val-heap-sub').innerText = ((d.heapUsedMb / d.heapCommittedMb) * 100).toFixed(1) + '% of committed';
        document.getElementById('val-threads').innerText = d.liveThreads;
        document.getElementById('val-gc').innerText = d.totalGcCount;
        document.getElementById('val-gc-time').innerText = d.totalGcTimeMs + ' ms total elapsed';

        document.getElementById('meter-used-text').innerText = 'Used: ' + d.heapUsedMb + ' MB';
        document.getElementById('meter-comm-text').innerText = 'Committed: ' + d.heapCommittedMb + ' MB';
        document.getElementById('meter-max-text').innerText = 'Max: ' + d.heapMaxMb + ' MB';

        const usedPct = (d.heapUsedMb / d.heapCommittedMb) * 100;
        document.getElementById('meter-used-bar').style.width = usedPct + '%';

        document.getElementById('val-nonheap').innerText = d.nonHeapUsedMb + ' MB';
        document.getElementById('val-peak-threads').innerText = d.peakThreads;
        document.getElementById('val-cores').innerText = d.availableProcessors;
        document.getElementById('val-uptime').innerText = d.uptimeSeconds + 's';

        document.getElementById('footer-java').innerText = d.javaVersion;
        document.getElementById('footer-os').innerText = d.osInfo;

        document.getElementById('alloc-status').innerText = 'Allocated: ' + d.webAllocatedMb + ' MB in heap';
      } catch (e) {}
    }

    async function loadTasks() {
      try {
        const res = await fetch('/api/tasks');
        const tasks = await res.json();
        const buildCards = (t) => `
          <div class="task-card">
            <div class="task-header">
              <strong style="color:#fff;">${t.name}</strong>
              <span class="badge badge-${t.state.toLowerCase()}">${t.state}</span>
            </div>
            <div style="font-size:11px; color:#94a3b8;">${t.description}</div>
            <div style="font-size:11px; color:#60a5fa;">${t.message}</div>
            ${t.state === 'RUNNING' || t.state === 'PAUSED' ? `
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" style="width: ${Math.max(5, (t.progress * 100))}%"></div>
              </div>
            ` : ''}
            <div style="display:flex; gap:8px; margin-top:8px;">
              <button class="btn btn-primary" onclick="runTask('${t.id}')" ${t.state === 'RUNNING' ? 'disabled' : ''}>Run</button>
              ${t.state === 'RUNNING' ? `<button class="btn btn-warning" onclick="pauseTask('${t.id}')">Pause</button>` : ''}
              ${t.state === 'PAUSED' ? `<button class="btn btn-primary" onclick="resumeTask('${t.id}')">Resume</button>` : ''}
              ${t.state === 'RUNNING' || t.state === 'PAUSED' ? `<button class="btn btn-danger" onclick="cancelTask('${t.id}')">Cancel</button>` : ''}
            </div>
          </div>
        `;
        document.getElementById('quick-tasks-container').innerHTML = tasks.slice(0, 4).map(buildCards).join('');
        document.getElementById('all-tasks-container').innerHTML = tasks.map(buildCards).join('');
      } catch (e) {}
    }

    async function loadHistory() {
      try {
        const res = await fetch('/api/history');
        const history = await res.json();
        const rows = history.map(h => `
          <tr>
            <td style="font-family:monospace; color:#60a5fa;">${h.executionId}</td>
            <td><strong>${h.taskName}</strong></td>
            <td>${h.category}</td>
            <td><span class="badge badge-${h.state.toLowerCase()}">${h.state}</span></td>
            <td>${h.durationMs} ms</td>
            <td>${h.itemsProcessed}</td>
            <td>${h.summary}</td>
          </tr>
        `).join('');
        document.getElementById('history-rows').innerHTML = rows || '<tr><td colspan="7">No history yet.</td></tr>';
      } catch (e) {}
    }

    async function runTask(id) { await fetch('/api/tasks/run?id=' + id, {method: 'POST'}); loadTasks(); }
    async function pauseTask(id) { await fetch('/api/tasks/pause?id=' + id, {method: 'POST'}); loadTasks(); }
    async function resumeTask(id) { await fetch('/api/tasks/resume?id=' + id, {method: 'POST'}); loadTasks(); }
    async function cancelTask(id) { await fetch('/api/tasks/cancel?id=' + id, {method: 'POST'}); loadTasks(); }

    async function requestGc() {
      const res = await fetch('/api/gc', {method: 'POST'});
      const d = await res.json();
      alert('JVM GC Request: ' + d.status + '\\n' + d.notice);
      pollMetrics();
    }

    async function allocChunk() {
      await fetch('/api/alloc?mb=20', {method: 'POST'});
      pollMetrics();
    }

    async function freeChunks() {
      await fetch('/api/free', {method: 'POST'});
      pollMetrics();
    }

    async function loadSamplePlugin() {
      const res = await fetch('/api/plugins/load-sample', {method: 'POST'});
      const d = await res.json();
      if (d.status === 'loaded') {
        alert('Plugin loaded dynamically: ' + d.name + ' (' + d.id + ')');
        document.getElementById('plugins-list').innerHTML = '<div style="color:#10b981; font-weight:bold;">Loaded: ' + d.name + ' (v1.0.0, API 1.0)</div>';
        loadTasks();
      } else {
        alert('Could not load plugin: ' + (d.error || 'Unknown error'));
      }
    }

    loadTasks();
    pollMetrics();
    setInterval(pollMetrics, 1000);
    setInterval(loadTasks, 1500);
  </script>
</body>
</html>
""";
    }
}

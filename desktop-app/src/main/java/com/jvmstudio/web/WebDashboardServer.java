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
<html lang="en" data-theme="light">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>JVM Studio — Live Task & Plugin Dashboard</title>
  <link rel="preconnect" href="https://fonts.googleapis.com">
  <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
  <link href="https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@300;400;500;600;700;800&family=JetBrains+Mono:wght@400;500;600&display=swap" rel="stylesheet">
  <script src="https://cdnjs.cloudflare.com/ajax/libs/gsap/3.12.5/gsap.min.js"></script>
  <style>
    :root {
      --font-sans: 'Plus Jakarta Sans', -apple-system, BlinkMacSystemFont, sans-serif;
      --font-mono: 'JetBrains Mono', monospace;

      /* Light Theme (Default) */
      --bg-page: #f8fafc;
      --bg-mesh: radial-gradient(at 0% 0%, rgba(99, 102, 241, 0.08) 0px, transparent 50%),
                 radial-gradient(at 100% 0%, rgba(168, 85, 247, 0.07) 0px, transparent 50%),
                 radial-gradient(at 50% 100%, rgba(14, 165, 233, 0.06) 0px, transparent 50%);
      --bg-surface: rgba(255, 255, 255, 0.85);
      --bg-surface-solid: #ffffff;
      --bg-card: #ffffff;
      --bg-card-hover: #ffffff;
      --bg-subtle: #f1f5f9;
      --border-color: #e2e8f0;
      --border-light: rgba(226, 232, 240, 0.8);
      
      --text-heading: #0f172a;
      --text-body: #334155;
      --text-muted: #64748b;
      --text-faint: #94a3b8;

      --grad-primary: linear-gradient(135deg, #4f46e5 0%, #7c3aed 100%);
      --grad-primary-hover: linear-gradient(135deg, #4338ca 0%, #6d28d9 100%);
      --grad-emerald: linear-gradient(135deg, #10b981 0%, #059669 100%);
      --grad-amber: linear-gradient(135deg, #f59e0b 0%, #ea580c 100%);
      --grad-rose: linear-gradient(135deg, #f43f5e 0%, #e11d48 100%);
      --grad-sky: linear-gradient(135deg, #0ea5e9 0%, #2563eb 100%);

      --shadow-sm: 0 1px 3px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.02);
      --shadow-card: 0 10px 30px -5px rgba(0, 0, 0, 0.04), 0 4px 6px -2px rgba(0, 0, 0, 0.02);
      --shadow-elevated: 0 20px 40px -10px rgba(79, 70, 229, 0.12), 0 8px 16px -4px rgba(0,0,0,0.04);
      --shadow-glow: 0 0 24px -2px rgba(99, 102, 241, 0.25);
    }

    [data-theme="dark"] {
      --bg-page: #0b0f19;
      --bg-mesh: radial-gradient(at 0% 0%, rgba(99, 102, 241, 0.15) 0px, transparent 50%),
                 radial-gradient(at 100% 0%, rgba(168, 85, 247, 0.12) 0px, transparent 50%),
                 radial-gradient(at 50% 100%, rgba(14, 165, 233, 0.1) 0px, transparent 50%);
      --bg-surface: rgba(17, 24, 39, 0.85);
      --bg-surface-solid: #111827;
      --bg-card: #141c2e;
      --bg-card-hover: #17223b;
      --bg-subtle: #1e293b;
      --border-color: #243048;
      --border-light: rgba(36, 48, 72, 0.8);
      
      --text-heading: #f8fafc;
      --text-body: #cbd5e1;
      --text-muted: #94a3b8;
      --text-faint: #64748b;

      --shadow-card: 0 10px 30px -5px rgba(0, 0, 0, 0.35);
      --shadow-elevated: 0 20px 40px -10px rgba(0, 0, 0, 0.5);
    }

    * { box-sizing: border-box; margin: 0; padding: 0; }

    body {
      font-family: var(--font-sans);
      background-color: var(--bg-page);
      background-image: var(--bg-mesh);
      background-attachment: fixed;
      color: var(--text-body);
      display: flex;
      min-height: 100vh;
      line-height: 1.5;
      overflow-x: hidden;
      transition: background-color 0.3s ease, color 0.3s ease;
    }

    /* Sidebar Navigation */
    .sidebar {
      width: 270px;
      background: var(--bg-surface);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border-right: 1px solid var(--border-light);
      padding: 28px 18px;
      display: flex;
      flex-direction: column;
      gap: 24px;
      position: sticky;
      top: 0;
      height: 100vh;
      z-index: 50;
    }

    .brand-wrap {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 0 6px 12px 6px;
      border-bottom: 1px solid var(--border-light);
    }

    .brand-badge {
      width: 40px;
      height: 40px;
      border-radius: 12px;
      background: var(--grad-primary);
      display: flex;
      align-items: center;
      justify-content: center;
      color: #fff;
      font-weight: 800;
      font-size: 18px;
      box-shadow: 0 8px 16px -2px rgba(79, 70, 229, 0.4);
    }

    .brand-title {
      font-size: 18px;
      font-weight: 800;
      color: var(--text-heading);
      letter-spacing: -0.5px;
    }

    .brand-sub {
      font-size: 10px;
      color: var(--text-faint);
      font-weight: 700;
      letter-spacing: 0.8px;
      text-transform: uppercase;
    }

    .nav-list { display: flex; flex-direction: column; gap: 6px; }

    .nav-item {
      padding: 10px 14px;
      border-radius: 10px;
      font-size: 13.5px;
      color: var(--text-muted);
      cursor: pointer;
      font-weight: 600;
      display: flex;
      align-items: center;
      gap: 12px;
      transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
      position: relative;
    }

    .nav-item:hover {
      background: var(--bg-subtle);
      color: var(--text-heading);
      transform: translateX(3px);
    }

    .nav-item.active {
      background: var(--bg-surface-solid);
      color: #4f46e5;
      font-weight: 700;
      box-shadow: var(--shadow-sm);
      border: 1px solid var(--border-light);
    }

    .nav-item.active::before {
      content: '';
      position: absolute;
      left: 0;
      top: 20%;
      height: 60%;
      width: 4px;
      border-radius: 0 4px 4px 0;
      background: var(--grad-primary);
    }

    .sidebar-footer {
      margin-top: auto;
      background: var(--bg-surface-solid);
      border: 1px solid var(--border-light);
      padding: 14px;
      border-radius: 12px;
      box-shadow: var(--shadow-sm);
    }

    .live-pulse {
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #10b981;
      margin-right: 6px;
      box-shadow: 0 0 10px #10b981;
      animation: pulseGlow 1.8s infinite;
    }

    @keyframes pulseGlow {
      0% { opacity: 0.5; transform: scale(0.9); }
      50% { opacity: 1; transform: scale(1.15); box-shadow: 0 0 14px #10b981; }
      100% { opacity: 0.5; transform: scale(0.9); }
    }

    /* Main Container */
    .main {
      flex: 1;
      display: flex;
      flex-direction: column;
      min-width: 0;
      overflow-y: auto;
    }

    .header {
      background: var(--bg-surface);
      backdrop-filter: blur(20px);
      -webkit-backdrop-filter: blur(20px);
      border-bottom: 1px solid var(--border-light);
      padding: 18px 36px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      position: sticky;
      top: 0;
      z-index: 40;
    }

    .header-title {
      font-size: 22px;
      font-weight: 800;
      color: var(--text-heading);
      letter-spacing: -0.5px;
    }

    .header-actions { display: flex; gap: 12px; align-items: center; }

    /* Interactive Buttons */
    .btn {
      padding: 9px 18px;
      border-radius: 10px;
      font-size: 13px;
      font-weight: 600;
      font-family: var(--font-sans);
      cursor: pointer;
      border: none;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
      text-decoration: none;
      outline: none;
    }

    .btn:active { transform: scale(0.97); }

    .btn-primary {
      background: var(--grad-primary);
      color: #ffffff;
      box-shadow: 0 4px 14px rgba(79, 70, 229, 0.35);
    }
    .btn-primary:hover {
      box-shadow: 0 6px 20px rgba(79, 70, 229, 0.45);
      transform: translateY(-1px);
    }

    .btn-warning {
      background: var(--grad-amber);
      color: #ffffff;
      box-shadow: 0 4px 14px rgba(245, 158, 11, 0.3);
    }
    .btn-warning:hover {
      box-shadow: 0 6px 18px rgba(245, 158, 11, 0.4);
      transform: translateY(-1px);
    }

    .btn-secondary {
      background: var(--bg-surface-solid);
      color: var(--text-heading);
      border: 1px solid var(--border-color);
      box-shadow: var(--shadow-sm);
    }
    .btn-secondary:hover {
      background: var(--bg-subtle);
      border-color: #cbd5e1;
      transform: translateY(-1px);
    }

    .btn-danger {
      background: var(--grad-rose);
      color: #ffffff;
      box-shadow: 0 4px 12px rgba(244, 63, 94, 0.3);
    }
    .btn-danger:hover {
      box-shadow: 0 6px 18px rgba(244, 63, 94, 0.45);
      transform: translateY(-1px);
    }

    .btn-theme-toggle {
      width: 38px;
      height: 38px;
      border-radius: 10px;
      padding: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      background: var(--bg-surface-solid);
      border: 1px solid var(--border-color);
      color: var(--text-heading);
      cursor: pointer;
      font-size: 16px;
      transition: all 0.2s;
    }
    .btn-theme-toggle:hover { background: var(--bg-subtle); transform: rotate(15deg); }

    /* Content Area */
    .content {
      padding: 36px;
      display: flex;
      flex-direction: column;
      gap: 28px;
      max-width: 1400px;
      width: 100%;
      margin: 0 auto;
    }

    /* Metric Tiles Grid */
    .metric-row {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(230px, 1fr));
      gap: 20px;
    }

    .metric-tile {
      background: var(--bg-card);
      border: 1px solid var(--border-light);
      border-radius: 16px;
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      box-shadow: var(--shadow-card);
      position: relative;
      overflow: hidden;
      transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    }

    .metric-tile:hover {
      transform: translateY(-4px);
      box-shadow: var(--shadow-elevated);
      border-color: rgba(99, 102, 241, 0.3);
    }

    .metric-tile::after {
      content: '';
      position: absolute;
      top: 0;
      left: 0;
      right: 0;
      height: 3px;
      background: var(--grad-primary);
      opacity: 0;
      transition: opacity 0.3s;
    }
    .metric-tile:hover::after { opacity: 1; }

    .metric-label {
      font-size: 11.5px;
      font-weight: 700;
      color: var(--text-faint);
      text-transform: uppercase;
      letter-spacing: 0.6px;
    }

    .metric-value {
      font-size: 32px;
      font-weight: 800;
      color: var(--text-heading);
      letter-spacing: -1px;
      line-height: 1.1;
    }

    .metric-sub {
      font-size: 12px;
      color: var(--text-muted);
      font-weight: 500;
    }

    /* Cards */
    .card {
      background: var(--bg-card);
      border: 1px solid var(--border-light);
      border-radius: 18px;
      padding: 26px;
      display: flex;
      flex-direction: column;
      gap: 18px;
      box-shadow: var(--shadow-card);
      transition: all 0.3s ease;
    }

    .card:hover {
      box-shadow: var(--shadow-elevated);
    }

    .card-title {
      font-size: 17px;
      font-weight: 800;
      color: var(--text-heading);
      letter-spacing: -0.3px;
    }

    .card-subtitle {
      font-size: 13px;
      color: var(--text-muted);
      line-height: 1.6;
    }

    /* Modern Gradient Heap Meter */
    .meter-container {
      display: flex;
      flex-direction: column;
      gap: 12px;
      padding: 16px;
      background: var(--bg-subtle);
      border-radius: 14px;
      border: 1px solid var(--border-light);
    }

    .meter-bar {
      height: 22px;
      background: var(--border-color);
      border-radius: 11px;
      overflow: hidden;
      display: flex;
      padding: 2px;
      position: relative;
    }

    .meter-used {
      background: var(--grad-primary);
      height: 100%;
      border-radius: 9px;
      width: 25%;
      transition: width 0.6s cubic-bezier(0.16, 1, 0.3, 1);
      position: relative;
      overflow: hidden;
    }

    .meter-used::after {
      content: '';
      position: absolute;
      top: 0; left: 0; bottom: 0; right: 0;
      background: linear-gradient(90deg, rgba(255,255,255,0) 0%, rgba(255,255,255,0.3) 50%, rgba(255,255,255,0) 100%);
      animation: shimmer 2s infinite;
    }

    @keyframes shimmer {
      0% { transform: translateX(-100%); }
      100% { transform: translateX(100%); }
    }

    /* Tasks Grid & Cards */
    .tasks-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
      gap: 22px;
    }

    .task-card {
      background: var(--bg-card);
      border: 1px solid var(--border-light);
      border-radius: 16px;
      padding: 22px;
      display: flex;
      flex-direction: column;
      gap: 14px;
      box-shadow: var(--shadow-card);
      transition: all 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    }

    .task-card:hover {
      transform: translateY(-3px);
      box-shadow: var(--shadow-elevated);
      border-color: rgba(99, 102, 241, 0.3);
    }

    .task-header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
    }

    .task-name {
      font-size: 16px;
      font-weight: 700;
      color: var(--text-heading);
    }

    .badge {
      padding: 4px 12px;
      border-radius: 20px;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.5px;
      text-transform: uppercase;
    }

    .badge-ready { background: #e2e8f0; color: #475569; }
    .badge-running { background: rgba(79, 70, 229, 0.12); color: #4f46e5; border: 1px solid rgba(79, 70, 229, 0.3); }
    .badge-completed { background: rgba(16, 185, 129, 0.12); color: #059669; border: 1px solid rgba(16, 185, 129, 0.3); }
    .badge-paused { background: rgba(245, 158, 11, 0.12); color: #d97706; border: 1px solid rgba(245, 158, 11, 0.3); }
    .badge-failed { background: rgba(244, 63, 94, 0.12); color: #e11d48; border: 1px solid rgba(244, 63, 94, 0.3); }
    .badge-cancelled { background: #f1f5f9; color: #94a3b8; }

    .progress-bar-bg {
      background: var(--bg-subtle);
      height: 8px;
      border-radius: 4px;
      overflow: hidden;
      margin-top: 6px;
      border: 1px solid var(--border-color);
    }

    .progress-bar-fill {
      background: var(--grad-primary);
      height: 100%;
      width: 0%;
      border-radius: 4px;
      transition: width 0.3s ease;
    }

    /* Table Styling */
    .table-wrap {
      overflow-x: auto;
      border: 1px solid var(--border-light);
      border-radius: 14px;
      background: var(--bg-card);
      box-shadow: var(--shadow-sm);
    }

    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th {
      text-align: left;
      padding: 14px 16px;
      background: var(--bg-subtle);
      color: var(--text-muted);
      font-weight: 700;
      text-transform: uppercase;
      font-size: 11px;
      letter-spacing: 0.5px;
      border-bottom: 1px solid var(--border-color);
    }
    td {
      padding: 13px 16px;
      border-bottom: 1px solid var(--border-light);
      color: var(--text-body);
    }
    tr:last-child td { border-bottom: none; }
    tr:hover td { background: var(--bg-subtle); }

    .screen-section { display: none; flex-direction: column; gap: 24px; }
    .screen-section.active { display: flex; }

    /* Learning Lab Comparison Cards */
    .lab-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(420px, 1fr));
      gap: 20px;
    }
    .lab-box-java {
      background: rgba(99, 102, 241, 0.05);
      border: 1px solid rgba(99, 102, 241, 0.2);
      border-radius: 12px;
      padding: 16px;
    }
    .lab-box-cpp {
      background: rgba(168, 85, 247, 0.05);
      border: 1px solid rgba(168, 85, 247, 0.2);
      border-radius: 12px;
      padding: 16px;
    }
  </style>
</head>
<body>
  <!-- Sidebar -->
  <aside class="sidebar">
    <div class="brand-wrap">
      <div class="brand-badge">⚡</div>
      <div>
        <div class="brand-title">JVM Studio</div>
        <div class="brand-sub">LIVE DASHBOARD</div>
      </div>
    </div>
    <nav class="nav-list">
      <div class="nav-item active" onclick="switchTab('overview')">
        <span>📊</span> Overview
      </div>
      <div class="nav-item" onclick="switchTab('tasks')">
        <span>⚙️</span> Tasks & Engine
      </div>
      <div class="nav-item" onclick="switchTab('plugins')">
        <span>🔌</span> Dynamic Plugins
      </div>
      <div class="nav-item" onclick="switchTab('monitor')">
        <span>📈</span> JVM Telemetry & GC
      </div>
      <div class="nav-item" onclick="switchTab('history')">
        <span>📜</span> Execution History
      </div>
      <div class="nav-item" onclick="switchTab('lab')">
        <span>🔬</span> Java vs C++ Lab
      </div>
      <div class="nav-item" onclick="switchTab('settings')">
        <span>🛠️</span> Settings & DB
      </div>
    </nav>
    <div class="sidebar-footer">
      <div style="display:flex; align-items:center; margin-bottom:4px;">
        <span class="live-pulse"></span>
        <span id="footer-java" style="font-weight:700; color:var(--text-heading); font-size:12px;">Java 21 LTS</span>
      </div>
      <div id="footer-os" style="color:var(--text-muted); font-size:11px;">Oracle HotSpot 64-Bit</div>
    </div>
  </aside>

  <!-- Main View -->
  <main class="main">
    <header class="header">
      <div>
        <h1 class="header-title" id="page-title">System Overview</h1>
      </div>
      <div class="header-actions">
        <span id="header-status" style="font-size:12px; background:var(--bg-subtle); padding:6px 12px; border-radius:20px; font-weight:600; color:var(--text-muted); border:1px solid var(--border-light);">
          Engine: Active
        </span>
        <button class="btn btn-warning" onclick="requestGc()">
          <span>🧹</span> Request GC (System.gc())
        </button>
        <button class="btn-theme-toggle" onclick="toggleTheme()" title="Toggle Dark/Light Mode" id="theme-btn">
          🌙
        </button>
      </div>
    </header>

    <div class="content">
      <!-- 1. OVERVIEW SCREEN -->
      <section id="tab-overview" class="screen-section active">
        <div class="metric-row">
          <div class="metric-tile">
            <div class="metric-label">Active Workers</div>
            <div class="metric-value" id="val-workers">0</div>
            <div class="metric-sub">Concurrent threads</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Heap Memory Used</div>
            <div class="metric-value" id="val-heap">0 MB</div>
            <div class="metric-sub" id="val-heap-sub">0% of committed</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Live Platform Threads</div>
            <div class="metric-value" id="val-threads">0</div>
            <div class="metric-sub">ThreadMXBean tracked</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">GC Collections</div>
            <div class="metric-value" id="val-gc">0</div>
            <div class="metric-sub" id="val-gc-time">0 ms pause time</div>
          </div>
        </div>

        <div class="card">
          <div class="card-title">Live JVM Heap Allocation Visualizer</div>
          <div class="meter-container">
            <div class="meter-bar">
              <div id="meter-used-bar" class="meter-used"></div>
            </div>
            <div style="display:flex; justify-content:space-between; font-size:12px; font-weight:600; color:var(--text-muted);">
              <span id="meter-used-text">Used: 0 MB</span>
              <span id="meter-comm-text">Committed: 0 MB</span>
              <span id="meter-max-text">Max Heap (-Xmx): 0 MB</span>
            </div>
          </div>
        </div>

        <div class="card">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div class="card-title">Orchestrated Tasks Quick Launcher</div>
              <div class="card-subtitle">Execute background workers with cooperative checkpoints and zero UI blocking.</div>
            </div>
            <button class="btn btn-secondary" onclick="switchTab('tasks')">View All Tasks →</button>
          </div>
          <div class="tasks-grid" id="quick-tasks-container"></div>
        </div>
      </section>

      <!-- 2. TASKS SCREEN -->
      <section id="tab-tasks" class="screen-section">
        <div class="card">
          <div class="card-title">Task Orchestration & Execution Hub</div>
          <div class="card-subtitle">Manage built-in tasks and dynamically loaded plugins with real progress, pause/resume, and cancellation checkpoints.</div>
          <div class="tasks-grid" id="all-tasks-container"></div>
        </div>
      </section>

      <!-- 3. PLUGINS SCREEN -->
      <section id="tab-plugins" class="screen-section">
        <div class="card" style="background: rgba(99, 102, 241, 0.05); border-color: rgba(99, 102, 241, 0.25);">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div style="font-size:13px; font-weight:800; color:#4f46e5; text-transform:uppercase; letter-spacing:0.5px;">Dynamic Bytecode Isolation Architecture</div>
              <div style="font-size:13px; color:var(--text-body); margin-top:4px;">
                Hot-loads external JAR files into an isolated <code>URLClassLoader</code> using <code>ServiceLoader</code> and reflection.
              </div>
            </div>
            <button class="btn btn-primary" onclick="loadSamplePlugin()">
              <span>📦</span> Load Sample Plugin JAR
            </button>
          </div>
        </div>

        <div class="card">
          <div class="card-title">Installed Dynamic Plugins</div>
          <div id="plugins-list" style="font-size:13.5px; color:var(--text-muted);">
            No external JAR loaded yet. Click "Load Sample Plugin JAR" to hot-load <code>WordFrequencyCounterPlugin</code> at runtime!
          </div>
        </div>
      </section>

      <!-- 4. JVM MONITOR SCREEN -->
      <section id="tab-monitor" class="screen-section">
        <div class="card">
          <div class="card-title">Interactive Heap Allocation & GC Observation Lab</div>
          <div class="card-subtitle">Allocate real 20 MB memory chunks into Eden space, hold GC roots, and observe garbage collection recovery.</div>
          <div style="display:flex; gap:12px; align-items:center; margin-top:6px; flex-wrap:wrap;">
            <button class="btn btn-primary" onclick="allocChunk()">+ Allocate 20 MB Chunk</button>
            <button class="btn btn-secondary" onclick="freeChunks()">Release References</button>
            <button class="btn btn-warning" onclick="requestGc()">Request GC (System.gc())</button>
            <span id="alloc-status" style="font-weight:700; color:#4f46e5; padding:6px 14px; background:rgba(79, 70, 229, 0.08); border-radius:20px;">
              Allocated: 0 MB
            </span>
          </div>
        </div>

        <div class="metric-row">
          <div class="metric-tile">
            <div class="metric-label">Metaspace / Non-Heap</div>
            <div class="metric-value" id="val-nonheap">0 MB</div>
            <div class="metric-sub">Class definitions & JIT cache</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Peak Threads</div>
            <div class="metric-value" id="val-peak-threads">0</div>
            <div class="metric-sub">Peak concurrency count</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">CPU Hardware Cores</div>
            <div class="metric-value" id="val-cores">0</div>
            <div class="metric-sub">Available execution processors</div>
          </div>
          <div class="metric-tile">
            <div class="metric-label">Uptime</div>
            <div class="metric-value" id="val-uptime">0s</div>
            <div class="metric-sub">Process continuous runtime</div>
          </div>
        </div>
      </section>

      <!-- 5. HISTORY SCREEN -->
      <section id="tab-history" class="screen-section">
        <div class="card">
          <div style="display:flex; justify-content:space-between; align-items:center;">
            <div>
              <div class="card-title">Execution Audit Records (SQLite WAL Engine)</div>
              <div class="card-subtitle">Every task execution is saved locally with timestamps, duration, and processed metrics.</div>
            </div>
            <button class="btn btn-secondary" onclick="loadHistory()">Refresh Records</button>
          </div>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Exec ID</th>
                  <th>Task Name</th>
                  <th>Category</th>
                  <th>Outcome</th>
                  <th>Duration</th>
                  <th>Items</th>
                  <th>Summary</th>
                </tr>
              </thead>
              <tbody id="history-rows"></tbody>
            </table>
          </div>
        </div>
      </section>

      <!-- 6. LEARNING LAB SCREEN -->
      <section id="tab-lab" class="screen-section">
        <div class="lab-grid">
          <div class="card">
            <div class="card-title">1. Dynamic Class Loading vs C++ dlopen</div>
            <div class="lab-box-java">
              <strong style="color:#4f46e5;">Java Runtime:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Hierarchical <code>URLClassLoader</code> loads standardized bytecode into Metaspace dynamically with bytecode verification.</p>
            </div>
            <div class="lab-box-cpp">
              <strong style="color:#7c3aed;">C++ Comparison:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Uses <code>dlopen/LoadLibrary</code>; fragile ABI without standard name mangling across compilers (requires <code>extern "C"</code>).</p>
            </div>
          </div>

          <div class="card">
            <div class="card-title">2. Reflection & Metadata vs C++ RTTI</div>
            <div class="lab-box-java">
              <strong style="color:#4f46e5;">Java Runtime:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Full runtime reflection (<code>java.lang.reflect</code>) inspects methods, constructors, and <code>@TaskInfo</code> metadata from constant pools.</p>
            </div>
            <div class="lab-box-cpp">
              <strong style="color:#7c3aed;">C++ Comparison:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Offers basic <code>typeid/dynamic_cast</code> but lacks standard member inspection without external preprocessors (like Qt MOC).</p>
            </div>
          </div>

          <div class="card">
            <div class="card-title">3. Garbage Collection vs Deterministic RAII</div>
            <div class="lab-box-java">
              <strong style="color:#4f46e5;">Java Runtime:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Generational GC automatically prevents dangling pointers and double-free, but requires <code>AutoCloseable</code> for OS handles.</p>
            </div>
            <div class="lab-box-cpp">
              <strong style="color:#7c3aed;">C++ Comparison:</strong>
              <p style="font-size:12.5px; margin-top:4px;">RAII with smart pointers (<code>std::unique_ptr</code>) cleans up deterministically at scope exit with zero GC pause overhead.</p>
            </div>
          </div>

          <div class="card">
            <div class="card-title">4. Bytecode Portability vs Native Machine Code</div>
            <div class="lab-box-java">
              <strong style="color:#4f46e5;">Java Runtime:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Bytecode runs unchanged across all OSs on any compliant JVM; HotSpot JIT (C1/C2) optimizes hot code to native assembly.</p>
            </div>
            <div class="lab-box-cpp">
              <strong style="color:#7c3aed;">C++ Comparison:</strong>
              <p style="font-size:12.5px; margin-top:4px;">Compiles directly to native CPU machine code (x86_64, ARM64) with zero VM overhead, but requires separate binaries per target.</p>
            </div>
          </div>
        </div>
      </section>

      <!-- 7. SETTINGS SCREEN -->
      <section id="tab-settings" class="screen-section">
        <div class="card">
          <div class="card-title">Architecture & Persistence Settings</div>
          <div class="card-subtitle">Configuration parameters stored in local SQLite database.</div>
          <div style="font-size:13.5px; line-height:2; color:var(--text-body); margin-top:10px;">
            <div><strong>Persistence Engine:</strong> SQLite 3 via JDBC in WAL Mode (Write-Ahead Logging)</div>
            <div><strong>Database File:</strong> <code>~/.jvmstudio/jvmstudio.db</code></div>
            <div><strong>Concurrency Pool:</strong> Bounded ThreadPoolExecutor (8 Worker Threads)</div>
            <div><strong>Default Collision Policy:</strong> Rename with timestamp suffix</div>
          </div>
        </div>
      </section>
    </div>
  </main>

  <script>
    // GSAP Tab Navigation with Smooth Animations
    function switchTab(name) {
      const current = document.querySelector('.screen-section.active');
      const target = document.getElementById('tab-' + name);
      if (current === target) return;

      document.querySelectorAll('.nav-item').forEach(n => n.classList.remove('active'));
      event.currentTarget.classList.add('active');

      document.getElementById('page-title').innerText = name.charAt(0).toUpperCase() + name.slice(1) + ' Dashboard';

      if (current) {
        gsap.to(current, {
          opacity: 0,
          y: -10,
          duration: 0.18,
          ease: 'power2.in',
          onComplete: () => {
            current.classList.remove('active');
            target.classList.add('active');
            gsap.fromTo(target, 
              { opacity: 0, y: 15, scale: 0.99 },
              { opacity: 1, y: 0, scale: 1, duration: 0.35, ease: 'power2.out' }
            );
            // Stagger cards in newly activated tab
            gsap.from(target.querySelectorAll('.card, .metric-tile'), {
              opacity: 0,
              y: 20,
              stagger: 0.06,
              duration: 0.4,
              ease: 'power2.out',
              delay: 0.05
            });
          }
        });
      } else {
        target.classList.add('active');
      }

      if (name === 'history') loadHistory();
      if (name === 'tasks') loadTasks();
    }

    // Theme Toggle (Light Default <-> Dark)
    function toggleTheme() {
      const html = document.documentElement;
      const isDark = html.getAttribute('data-theme') === 'dark';
      const newTheme = isDark ? 'light' : 'dark';
      html.setAttribute('data-theme', newTheme);
      document.getElementById('theme-btn').innerText = isDark ? '🌙' : '☀️';
      
      gsap.fromTo('body', { opacity: 0.95 }, { opacity: 1, duration: 0.25 });
    }

    // Real-Time Telemetry Polling
    async function pollMetrics() {
      try {
        const res = await fetch('/api/metrics');
        const d = await res.json();
        
        document.getElementById('val-workers').innerText = d.activeTasks;
        document.getElementById('val-heap').innerText = d.heapUsedMb + ' MB';
        document.getElementById('val-heap-sub').innerText = ((d.heapUsedMb / d.heapCommittedMb) * 100).toFixed(1) + '% of committed';
        document.getElementById('val-threads').innerText = d.liveThreads;
        document.getElementById('val-gc').innerText = d.totalGcCount;
        document.getElementById('val-gc-time').innerText = d.totalGcTimeMs + ' ms pause time';

        document.getElementById('meter-used-text').innerText = 'Used: ' + d.heapUsedMb + ' MB';
        document.getElementById('meter-comm-text').innerText = 'Committed: ' + d.heapCommittedMb + ' MB';
        document.getElementById('meter-max-text').innerText = 'Max (-Xmx): ' + d.heapMaxMb + ' MB';

        const usedPct = Math.min(100, (d.heapUsedMb / d.heapCommittedMb) * 100);
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

    // Dynamic Task Cards
    async function loadTasks() {
      try {
        const res = await fetch('/api/tasks');
        const tasks = await res.json();
        const renderCard = (t) => `
          <div class="task-card">
            <div class="task-header">
              <div>
                <div class="task-name">${t.name}</div>
                <div style="font-size:11px; color:var(--text-faint); margin-top:2px;">ID: ${t.id} | v${t.version}</div>
              </div>
              <span class="badge badge-${t.state.toLowerCase()}">${t.state}</span>
            </div>
            <div style="font-size:12px; color:var(--text-muted); line-height:1.5;">${t.description}</div>
            <div style="font-size:11.5px; font-weight:600; color:#4f46e5;">${t.message}</div>
            ${t.state === 'RUNNING' || t.state === 'PAUSED' ? `
              <div class="progress-bar-bg">
                <div class="progress-bar-fill" style="width: ${Math.max(5, (t.progress * 100))}%"></div>
              </div>
            ` : ''}
            <div style="display:flex; gap:8px; margin-top:10px;">
              <button class="btn btn-primary" onclick="runTask('${t.id}')" ${t.state === 'RUNNING' ? 'disabled' : ''}>Run Task</button>
              ${t.state === 'RUNNING' ? `<button class="btn btn-warning" onclick="pauseTask('${t.id}')">Pause</button>` : ''}
              ${t.state === 'PAUSED' ? `<button class="btn btn-primary" onclick="resumeTask('${t.id}')">Resume</button>` : ''}
              ${t.state === 'RUNNING' || t.state === 'PAUSED' ? `<button class="btn btn-danger" onclick="cancelTask('${t.id}')">Cancel</button>` : ''}
            </div>
          </div>
        `;
        document.getElementById('quick-tasks-container').innerHTML = tasks.slice(0, 4).map(renderCard).join('');
        document.getElementById('all-tasks-container').innerHTML = tasks.map(renderCard).join('');
      } catch (e) {}
    }

    // Execution History
    async function loadHistory() {
      try {
        const res = await fetch('/api/history');
        const history = await res.json();
        const rows = history.map(h => `
          <tr>
            <td style="font-family:var(--font-mono); font-weight:600; color:#4f46e5;">${h.executionId}</td>
            <td><strong>${h.taskName}</strong></td>
            <td><span style="font-size:11px; color:var(--text-muted);">${h.category}</span></td>
            <td><span class="badge badge-${h.state.toLowerCase()}">${h.state}</span></td>
            <td style="font-family:var(--font-mono);">${h.durationMs} ms</td>
            <td>${h.itemsProcessed}</td>
            <td style="color:var(--text-muted);">${h.summary}</td>
          </tr>
        `).join('');
        document.getElementById('history-rows').innerHTML = rows || '<tr><td colspan="7" style="text-align:center; padding:24px; color:var(--text-muted);">No executions recorded yet.</td></tr>';
      } catch (e) {}
    }

    // Action Handlers
    async function runTask(id) {
      await fetch('/api/tasks/run?id=' + id, {method: 'POST'});
      loadTasks();
    }
    async function pauseTask(id) {
      await fetch('/api/tasks/pause?id=' + id, {method: 'POST'});
      loadTasks();
    }
    async function resumeTask(id) {
      await fetch('/api/tasks/resume?id=' + id, {method: 'POST'});
      loadTasks();
    }
    async function cancelTask(id) {
      await fetch('/api/tasks/cancel?id=' + id, {method: 'POST'});
      loadTasks();
    }

    async function requestGc() {
      const res = await fetch('/api/gc', {method: 'POST'});
      const d = await res.json();
      alert('JVM Garbage Collection: ' + d.status + '\\n' + d.notice);
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
        alert('Dynamic Plugin Loaded: ' + d.name + ' (' + d.id + ')');
        document.getElementById('plugins-list').innerHTML = `
          <div style="padding:16px; background:rgba(16,185,129,0.08); border:1px solid rgba(16,185,129,0.3); border-radius:12px; color:#059669; font-weight:700;">
            ✓ Active Dynamic Plugin: ${d.name} (v1.0.0, API 1.0)
            <div style="font-size:12px; font-weight:500; color:var(--text-muted); margin-top:4px;">URLClassLoader isolation active. New task card created on Tasks screen.</div>
          </div>
        `;
        loadTasks();
      } else {
        alert('Could not load plugin: ' + (d.error || 'Unknown error'));
      }
    }

    // Initialize
    loadTasks();
    pollMetrics();
    setInterval(pollMetrics, 1000);
    setInterval(loadTasks, 1500);

    // Initial GSAP Reveal
    gsap.from('.header', { y: -20, opacity: 0, duration: 0.5, ease: 'power2.out' });
    gsap.from('.metric-tile', { y: 20, opacity: 0, stagger: 0.08, duration: 0.5, ease: 'power2.out', delay: 0.1 });
    gsap.from('.card', { y: 25, opacity: 0, stagger: 0.1, duration: 0.6, ease: 'power2.out', delay: 0.2 });
  </script>
</body>
</html>
""";
    }
}

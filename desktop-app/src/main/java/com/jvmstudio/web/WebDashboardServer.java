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
        try (var in = getClass().getResourceAsStream("/web/index.html")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        try {
            Path p = Path.of("desktop-app/src/main/resources/web/index.html");
            if (java.nio.file.Files.exists(p)) {
                return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "<!DOCTYPE html><html><body><h1>JVM Studio Dashboard</h1></body></html>";
    }
}

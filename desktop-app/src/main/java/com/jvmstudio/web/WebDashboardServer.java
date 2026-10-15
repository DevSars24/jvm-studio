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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        server.createContext("/api/compile-run", this::handleCompileRun);
        server.createContext("/assets/", this::handleAssets);

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

        long totalWebAllocatedMb = 0;
        synchronized (webMemoryHolder) {
            for (byte[] b : webMemoryHolder) {
                totalWebAllocatedMb += (b.length / (1024L * 1024L));
            }
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
                totalWebAllocatedMb
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
            int mb = 20;
            Map<String, String> query = parseQuery(exchange.getRequestURI().getQuery());
            if (query.containsKey("mb")) {
                try {
                    int requested = Integer.parseInt(query.get("mb"));
                    if (requested > 0 && requested <= 20) {
                        mb = requested;
                    }
                } catch (NumberFormatException ignored) {}
            }
            byte[] chunk = new byte[mb * 1024 * 1024];
            chunk[0] = 7;
            chunk[chunk.length - 1] = 7;
            webMemoryHolder.add(chunk);
            long totalWebAllocatedMb = 0;
            synchronized (webMemoryHolder) {
                for (byte[] b : webMemoryHolder) {
                    totalWebAllocatedMb += (b.length / (1024L * 1024L));
                }
            }
            sendResponse(exchange, 200, "application/json", String.format(Locale.ROOT,
                    "{\"status\":\"allocated\",\"allocatedMb\":%d,\"chunks\":%d,\"totalMb\":%d}",
                    mb, webMemoryHolder.size(), totalWebAllocatedMb));
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

    private void handleCompileRun(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
            exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "application/json", "{\"error\":\"Method Not Allowed\"}");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String code = body;
        if (body.trim().startsWith("{") && body.contains("\"code\"")) {
            try {
                int codeKeyIdx = body.indexOf("\"code\"");
                int colonIdx = body.indexOf(":", codeKeyIdx);
                int firstQuote = body.indexOf("\"", colonIdx);
                if (firstQuote != -1) {
                    StringBuilder sb = new StringBuilder();
                    boolean escaped = false;
                    for (int i = firstQuote + 1; i < body.length(); i++) {
                        char c = body.charAt(i);
                        if (escaped) {
                            if (c == 'n') sb.append('\n');
                            else if (c == 'r') sb.append('\r');
                            else if (c == 't') sb.append('\t');
                            else if (c == '"') sb.append('\"');
                            else if (c == '\\') sb.append('\\');
                            else sb.append(c);
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if (c == '"') {
                            break;
                        } else {
                            sb.append(c);
                        }
                    }
                    code = sb.toString();
                }
            } catch (Exception ignored) {}
        }

        if (code == null || code.isBlank()) {
            sendResponse(exchange, 400, "application/json", "{\"error\":\"No Java source code provided\"}");
            return;
        }

        String className = "Main";
        Matcher classMatcher = Pattern.compile("(?:public\\s+)?class\\s+([A-Za-z0-9_$]+)").matcher(code);
        if (classMatcher.find()) {
            className = classMatcher.group(1);
        }

        Path tempDir = Files.createTempDirectory("jvmstudio_compile_");
        try {
            Path javaFile = tempDir.resolve(className + ".java");
            Files.writeString(javaFile, code, StandardCharsets.UTF_8);

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean success = false;
            StringBuilder compilerErrors = new StringBuilder();

            if (compiler != null) {
                try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
                    Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjects(javaFile.toFile());
                    JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, units);
                    success = Boolean.TRUE.equals(task.call());
                }
            } else {
                String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator;
                String javacCmd = javaBin + (System.getProperty("os.name").toLowerCase().contains("win") ? "javac.exe" : "javac");
                Process proc = new ProcessBuilder(javacCmd, javaFile.toString()).directory(tempDir.toFile()).start();
                String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                success = (proc.waitFor() == 0);
                if (!success) compilerErrors.append(err);
            }

            if (!success) {
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    compilerErrors.append(String.format("Line %d: %s\n", d.getLineNumber(), d.getMessage(Locale.ROOT)));
                }
                String errJson = String.format(Locale.ROOT,
                        "{\"success\":false,\"className\":\"%s\",\"errors\":\"%s\",\"stdout\":\"\",\"disassembly\":\"\",\"opcodes\":[]}",
                        className, escapeJson(compilerErrors.toString().trim()));
                sendResponse(exchange, 200, "application/json", errJson);
                return;
            }

            // Compilation Succeeded! Run javap -c -p
            String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator;
            String javapCmd = javaBin + (System.getProperty("os.name").toLowerCase().contains("win") ? "javap.exe" : "javap");
            Process javapProc = new ProcessBuilder(javapCmd, "-c", "-p", className)
                    .directory(tempDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String javapOutput = new String(javapProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            javapProc.waitFor(5, TimeUnit.SECONDS);

            // Execute the compiled class to get real output
            String javaCmd = javaBin + (System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java");
            Process runProc = new ProcessBuilder(javaCmd, "-cp", tempDir.toString(), className)
                    .directory(tempDir.toFile())
                    .start();
            boolean exited = runProc.waitFor(4, TimeUnit.SECONDS);
            String runStdout = "";
            String runStderr = "";
            if (!exited) {
                runProc.destroyForcibly();
                runStdout = "[Terminated: Execution exceeded 4-second safety timeout]";
            } else {
                runStdout = new String(runProc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                runStderr = new String(runProc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            String fullStdout = runStdout + (runStderr.isBlank() ? "" : ("\nError: " + runStderr));
            List<String> opcodeJsonItems = parseJavapOpcodes(javapOutput);

            String jsonResponse = String.format(Locale.ROOT,
                    "{\"success\":true,\"className\":\"%s\",\"stdout\":\"%s\",\"disassembly\":\"%s\",\"opcodes\":[%s]}",
                    className,
                    escapeJson(fullStdout),
                    escapeJson(javapOutput),
                    String.join(",", opcodeJsonItems)
            );

            sendResponse(exchange, 200, "application/json", jsonResponse);
        } catch (Exception e) {
            logger.error("Error during compile-run handler", e);
            sendResponse(exchange, 500, "application/json", "{\"success\":false,\"errors\":\"" + escapeJson(e.getMessage()) + "\"}");
        } finally {
            try {
                try (var s = Files.walk(tempDir)) {
                    s.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            } catch (Exception ignored) {}
        }
    }

    private List<String> parseJavapOpcodes(String javapOutput) {
        List<String> list = new ArrayList<>();
        if (javapOutput == null) return list;

        Pattern pattern = Pattern.compile("^\\s*(\\d+):\\s+([a-z0-9_]+)(?:\\s+([^/\\n\\r]+?))?(?:\\s*//.*)?$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(javapOutput);

        while (matcher.find()) {
            int offset = Integer.parseInt(matcher.group(1));
            String mnemonic = matcher.group(2).trim();
            String rawArg = matcher.group(3) != null ? matcher.group(3).trim() : "";
            rawArg = rawArg.replaceAll("\\s+", " ");
            String desc = getOpcodeDescription(mnemonic, rawArg);

            String item = String.format(Locale.ROOT,
                    "{\"offset\":%d,\"op\":\"%s\",\"desc\":\"%s\",\"mnemonic\":\"%s\",\"arg\":\"%s\"}",
                    offset,
                    escapeJson(mnemonic + (rawArg.isEmpty() ? "" : " " + rawArg)),
                    escapeJson(desc),
                    escapeJson(mnemonic),
                    escapeJson(rawArg)
            );
            list.add(item);
        }
        return list;
    }

    private String getOpcodeDescription(String mnemonic, String arg) {
        if (mnemonic.startsWith("bipush") || mnemonic.startsWith("sipush")) return "Push " + arg + " onto Operand Stack";
        if (mnemonic.startsWith("iconst_m1")) return "Push constant -1 onto Operand Stack";
        if (mnemonic.startsWith("iconst_")) return "Push constant " + mnemonic.substring(7) + " onto Operand Stack";
        if (mnemonic.startsWith("ldc")) return "Load constant " + arg + " from Constant Pool";
        if (mnemonic.startsWith("iload_")) return "Load int from Local Variable #" + mnemonic.substring(6) + " onto stack";
        if (mnemonic.equals("iload")) return "Load int from Local Variable #" + arg + " onto stack";
        if (mnemonic.startsWith("aload_")) return "Load reference from Local Variable #" + mnemonic.substring(6) + " onto stack";
        if (mnemonic.equals("aload")) return "Load reference from Local Variable #" + arg + " onto stack";
        if (mnemonic.startsWith("istore_")) return "Pop int into Local Variable #" + mnemonic.substring(7);
        if (mnemonic.equals("istore")) return "Pop int into Local Variable #" + arg;
        if (mnemonic.startsWith("astore_")) return "Pop reference into Local Variable #" + mnemonic.substring(7);
        if (mnemonic.equals("astore")) return "Pop reference into Local Variable #" + arg;
        if (mnemonic.equals("iadd")) return "Pop top 2 values, add them, push sum";
        if (mnemonic.equals("isub")) return "Pop top 2 values, subtract, push difference";
        if (mnemonic.equals("imul")) return "Pop top 2 values, multiply, push product";
        if (mnemonic.equals("idiv")) return "Pop top 2 values, divide, push quotient";
        if (mnemonic.equals("irem")) return "Pop top 2 values, compute modulo (%), push remainder";
        if (mnemonic.equals("iinc")) return "Increment local variable " + arg;
        if (mnemonic.equals("dup")) return "Duplicate top value on Operand Stack";
        if (mnemonic.equals("new")) return "Allocate uninitialized " + arg + " on Heap";
        if (mnemonic.equals("invokespecial")) return "Invoke instance initializer " + arg;
        if (mnemonic.equals("invokevirtual")) return "Dynamically dispatch " + arg + " via vtable";
        if (mnemonic.equals("invokestatic")) return "Invoke static method " + arg;
        if (mnemonic.equals("invokeinterface")) return "Invoke interface method " + arg + " via itable";
        if (mnemonic.equals("invokedynamic")) return "Invoke bootstrap dynamic call site " + arg;
        if (mnemonic.equals("getstatic")) return "Resolve static field " + arg + " from Constant Pool";
        if (mnemonic.equals("putstatic")) return "Assign static field " + arg;
        if (mnemonic.equals("getfield")) return "Fetch field " + arg + " from object";
        if (mnemonic.equals("putfield")) return "Assign field " + arg + " on object";
        if (mnemonic.equals("return")) return "Teardown stack frame and return void";
        if (mnemonic.equals("ireturn") || mnemonic.equals("areturn")) return "Return top value to caller";
        if (mnemonic.startsWith("if_") || mnemonic.startsWith("if")) return "Branch conditionally to " + arg;
        return "Execute opcode " + mnemonic + (arg.isEmpty() ? "" : " " + arg);
    }

    private void handleAssets(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String filename = path.replace("/assets/", "");
        if (filename.contains("..") || filename.contains("/") || filename.contains("\\")) {
            filename = Path.of(filename).getFileName().toString();
        }
        Path localPath = Path.of("desktop-app/src/main/resources/web/assets", filename);
        if (!Files.exists(localPath)) {
            localPath = Path.of("assets", filename);
        }
        if (Files.exists(localPath) && !Files.isDirectory(localPath)) {
            byte[] bytes = Files.readAllBytes(localPath);
            String contentType = filename.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            sendResponse(exchange, 404, "text/plain", "Asset not found: " + filename);
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
        try {
            Path p = Path.of("desktop-app/src/main/resources/web/index.html");
            if (java.nio.file.Files.exists(p)) {
                return java.nio.file.Files.readString(p, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        try (var in = getClass().getResourceAsStream("/web/index.html")) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {}
        return "<!DOCTYPE html><html><body><h1>JVM Studio Dashboard</h1></body></html>";
    }
}

package com.jvmstudio.core.engine;

import com.jvmstudio.plugin.api.TaskContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Concrete implementation of {@link TaskContext} for the JVM Studio execution engine.
 * Implements cooperative pause/resume, cooperative cancellation, and event throttling.
 */
public class TaskExecutionContextImpl implements TaskContext {
    private static final Logger logger = LoggerFactory.getLogger(TaskExecutionContextImpl.class);
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int MAX_LOG_LINES = 1000;
    private static final long THROTTLE_INTERVAL_MS = 60;

    private final String taskId;
    private final String executionId;
    private final Consumer<ProgressEvent> progressConsumer;
    private final Consumer<String> logConsumer;
    private final Consumer<Boolean> pauseStateConsumer;

    private final ReentrantLock pauseLock = new ReentrantLock();
    private final Condition unpausedCondition = pauseLock.newCondition();

    private volatile boolean cancelled = false;
    private volatile boolean paused = false;

    private volatile double lastReportedDone = -1;
    private volatile double lastReportedTotal = -1;
    private volatile String lastReportedMessage = "";
    private volatile long lastProgressDispatchTime = 0;

    private final List<String> logBuffer = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> resultData = new ConcurrentHashMap<>();

    public record ProgressEvent(String taskId, String executionId, double workDone, double totalWork, String message) {}

    public TaskExecutionContextImpl(String taskId, String executionId,
                                  Consumer<ProgressEvent> progressConsumer,
                                  Consumer<String> logConsumer,
                                  Consumer<Boolean> pauseStateConsumer) {
        this.taskId = taskId;
        this.executionId = executionId;
        this.progressConsumer = progressConsumer;
        this.logConsumer = logConsumer;
        this.pauseStateConsumer = pauseStateConsumer;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getExecutionId() {
        return executionId;
    }

    @Override
    public void updateProgress(double workDone, double totalWork) {
        this.lastReportedDone = workDone;
        this.lastReportedTotal = totalWork;
        dispatchThrottledProgress(false);
    }

    @Override
    public void updateMessage(String message) {
        this.lastReportedMessage = message != null ? message : "";
        dispatchThrottledProgress(false);
    }

    public void flushProgress() {
        dispatchThrottledProgress(true);
    }

    private void dispatchThrottledProgress(boolean force) {
        long now = System.currentTimeMillis();
        boolean isComplete = (lastReportedTotal > 0 && lastReportedDone >= lastReportedTotal);
        if (force || isComplete || (now - lastProgressDispatchTime >= THROTTLE_INTERVAL_MS)) {
            lastProgressDispatchTime = now;
            if (progressConsumer != null) {
                progressConsumer.accept(new ProgressEvent(taskId, executionId, lastReportedDone, lastReportedTotal, lastReportedMessage));
            }
        }
    }

    @Override
    public void log(String message) {
        appendLog("INFO", message, null);
    }

    @Override
    public void logWarn(String message) {
        appendLog("WARN", message, null);
    }

    @Override
    public void logError(String message, Throwable throwable) {
        appendLog("ERROR", message, throwable);
    }

    private void appendLog(String level, String msg, Throwable t) {
        String time = LocalTime.now().format(TIME_FMT);
        StringBuilder sb = new StringBuilder("[").append(time).append("] [").append(level).append("] ").append(msg != null ? msg : "");
        if (t != null) {
            sb.append(" (").append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(")");
        }
        String entry = sb.toString();

        synchronized (logBuffer) {
            if (logBuffer.size() >= MAX_LOG_LINES) {
                logBuffer.remove(0);
            }
            logBuffer.add(entry);
        }

        if (logConsumer != null) {
            logConsumer.accept(entry);
        }
    }

    @Override
    public void checkCancellation() throws InterruptedException {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Task execution cancelled cooperatively.");
        }
    }

    @Override
    public void checkPauseAndCancel() throws InterruptedException {
        checkCancellation();
        if (paused) {
            pauseLock.lockInterruptibly();
            try {
                while (paused && !cancelled && !Thread.currentThread().isInterrupted()) {
                    log("Task execution paused at checkpoint. Awaiting resume signal...");
                    unpausedCondition.await();
                }
            } finally {
                pauseLock.unlock();
            }
            checkCancellation();
            log("Task resumed execution from checkpoint.");
        }
    }

    public void pause() {
        pauseLock.lock();
        try {
            if (!paused && !cancelled) {
                paused = true;
                if (pauseStateConsumer != null) {
                    pauseStateConsumer.accept(true);
                }
            }
        } finally {
            pauseLock.unlock();
        }
    }

    public void resume() {
        pauseLock.lock();
        try {
            if (paused) {
                paused = false;
                unpausedCondition.signalAll();
                if (pauseStateConsumer != null) {
                    pauseStateConsumer.accept(false);
                }
            }
        } finally {
            pauseLock.unlock();
        }
    }

    public void cancel() {
        pauseLock.lock();
        try {
            cancelled = true;
            paused = false;
            unpausedCondition.signalAll();
        } finally {
            pauseLock.unlock();
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled || Thread.currentThread().isInterrupted();
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public void putResultData(String key, Object value) {
        if (key != null && value != null) {
            resultData.put(key, value);
        }
    }

    @Override
    public Object getResultData(String key) {
        return resultData.get(key);
    }

    @Override
    public Map<String, Object> getAllResultData() {
        return Collections.unmodifiableMap(resultData);
    }

    public List<String> getLogBufferSnapshot() {
        synchronized (logBuffer) {
            return new ArrayList<>(logBuffer);
        }
    }

    public String getLogBufferText() {
        synchronized (logBuffer) {
            return String.join("\n", logBuffer);
        }
    }

    public double getLastReportedDone() {
        return lastReportedDone;
    }

    public double getLastReportedTotal() {
        return lastReportedTotal;
    }

    public String getLastReportedMessage() {
        return lastReportedMessage;
    }
}

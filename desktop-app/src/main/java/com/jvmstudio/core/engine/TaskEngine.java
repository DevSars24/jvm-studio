package com.jvmstudio.core.engine;

import com.jvmstudio.core.db.TaskHistoryRepository;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskSchedule;
import com.jvmstudio.core.model.TaskScheduleType;
import com.jvmstudio.core.model.TaskState;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Robust, concurrent background task execution and scheduling engine.
 * Implements bounded worker threads, cooperative pause/cancellation,
 * schedule overlap prevention, and lifecycle state management.
 */
public class TaskEngine {
    private static final Logger logger = LoggerFactory.getLogger(TaskEngine.class);

    private final TaskHistoryRepository historyRepository;
    private final ThreadPoolExecutor workerPool;
    private final ScheduledExecutorService scheduler;

    private final Map<String, TaskHandle> activeTasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledFutures = new ConcurrentHashMap<>();
    private final List<TaskEngineListener> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean isShuttingDown = false;

    public TaskEngine(int threadPoolSize, TaskHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
        int poolSize = Math.max(1, threadPoolSize);

        AtomicInteger threadId = new AtomicInteger(1);
        ThreadFactory workerThreadFactory = r -> {
            Thread t = new Thread(r, "jvmstudio-worker-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        };

        this.workerPool = new ThreadPoolExecutor(
                poolSize,
                poolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(200),
                workerThreadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );

        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "jvmstudio-scheduler-" + threadId.getAndIncrement());
            t.setDaemon(true);
            return t;
        });

        logger.info("TaskEngine initialized with pool size = {}", poolSize);
    }

    public void addListener(TaskEngineListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(TaskEngineListener listener) {
        listeners.remove(listener);
    }

    public int getActiveTaskCount() {
        return (int) activeTasks.values().stream().filter(h -> h.getState().isActive()).count();
    }

    public int getPoolSize() {
        return workerPool.getMaximumPoolSize();
    }

    public void setPoolSize(int size) {
        int bounded = Math.max(1, Math.min(32, size));
        workerPool.setCorePoolSize(bounded);
        workerPool.setMaximumPoolSize(bounded);
        logger.info("TaskEngine pool size updated to {}", bounded);
    }

    public TaskHandle getActiveTaskHandle(String taskId) {
        return activeTasks.get(taskId);
    }

    public List<TaskHandle> getAllActiveTasks() {
        return new ArrayList<>(activeTasks.values());
    }

    public boolean isTaskRunningOrPaused(String taskId) {
        TaskHandle handle = activeTasks.get(taskId);
        return handle != null && handle.getState().isActive();
    }

    /**
     * Executes a task instance. Validates overlap policy before starting.
     *
     * @param def             the task definition
     * @param overrideParams  optional parameter overrides (or null to use configured parameters)
     * @return the unique execution ID
     */
    public synchronized String runTask(TaskDefinition def, Map<String, Object> overrideParams) {
        if (isShuttingDown) {
            throw new IllegalStateException("TaskEngine is shutting down; cannot accept new tasks.");
        }

        String taskId = def.getId();

        // DOCUMENTED OVERLAP POLICY:
        // If a task with this ID is currently RUNNING or PAUSED, skip this execution.
        if (isTaskRunningOrPaused(taskId)) {
            String msg = "Task '" + def.getName() + "' [" + taskId + "] is already " +
                    activeTasks.get(taskId).getState() + ". Overlap policy: execution skipped.";
            logger.warn(msg);
            notifyLog(taskId, "SYSTEM", "[OVERLAP POLICY] " + msg);
            return null;
        }

        Map<String, Object> params = new HashMap<>(def.getConfiguredParameters());
        if (overrideParams != null) {
            params.putAll(overrideParams);
        }

        // Validate parameters
        def.getPlugin().validateParameters(params);

        String executionId = UUID.randomUUID().toString().substring(0, 8);
        Instant startTime = Instant.now();

        TaskExecutionContextImpl context = new TaskExecutionContextImpl(
                taskId,
                executionId,
                event -> handleProgressEvent(event),
                logLine -> notifyLog(taskId, executionId, logLine),
                isPaused -> handlePauseStateChanged(taskId, executionId, isPaused)
        );

        TaskHandle handle = new TaskHandle(executionId, def, context, startTime);
        activeTasks.put(taskId, handle);

        // Record start in history repository
        if (historyRepository != null) {
            historyRepository.insert(TaskHistoryRecord.createRunning(
                    executionId, taskId, def.getName(), def.getCategory(), startTime
            ));
        }

        notifyStateChange(taskId, executionId, TaskState.READY, TaskState.RUNNING, "Starting task execution...");

        Future<?> future = workerPool.submit(() -> executeWorker(handle, params));
        handle.setFuture(future);

        return executionId;
    }

    private void executeWorker(TaskHandle handle, Map<String, Object> params) {
        String taskId = handle.getTaskId();
        String executionId = handle.getExecutionId();
        TaskDefinition def = handle.getDefinition();
        TaskExecutionContextImpl context = handle.getContext();

        TaskExecutionResult result = null;
        Throwable error = null;

        try {
            context.log("Worker thread [" + Thread.currentThread().getName() + "] dispatched for task: " + def.getName());
            result = def.getPlugin().execute(context, params);
        } catch (InterruptedException ie) {
            context.logWarn("Task was interrupted/cancelled cooperatively: " + ie.getMessage());
            result = TaskExecutionResult.cancelled(handle.getItemsProcessed(), handle.getBytesProcessed(), "Cancelled cooperatively", handle.getElapsedDurationMs());
        } catch (Throwable t) {
            logger.error("Execution failed for task {}", taskId, t);
            context.logError("Execution failed: " + t.getMessage(), t);
            error = t;
            result = TaskExecutionResult.failed(t.getMessage(), t, handle.getElapsedDurationMs());
        } finally {
            context.flushProgress();
            finalizeExecution(handle, result, error);
        }
    }

    private void finalizeExecution(TaskHandle handle, TaskExecutionResult result, Throwable error) {
        String taskId = handle.getTaskId();
        String executionId = handle.getExecutionId();
        Instant endTime = Instant.now();
        long durationMs = handle.getElapsedDurationMs();

        TaskState finalState;
        if (result != null && result.status() == TaskExecutionStatus.SUCCESS) {
            finalState = TaskState.COMPLETED;
        } else if (result != null && result.status() == TaskExecutionStatus.CANCELLED) {
            finalState = TaskState.CANCELLED;
        } else {
            finalState = TaskState.FAILED;
        }

        TaskState oldState = handle.getState();
        handle.setState(finalState);
        handle.setSummary(result != null ? result.summary() : "Completed");
        handle.setErrorMessage(result != null ? result.errorMessage() : (error != null ? error.getMessage() : null));
        if (result != null) {
            handle.setItemsProcessed(result.itemsProcessed());
            handle.setBytesProcessed(result.bytesProcessed());
        }

        // Update database history record before publishing completion notification
        if (historyRepository != null) {
            TaskHistoryRecord record = new TaskHistoryRecord(
                    executionId,
                    taskId,
                    handle.getTaskName(),
                    handle.getDefinition().getCategory(),
                    handle.getStartTime(),
                    endTime,
                    durationMs,
                    finalState,
                    handle.getItemsProcessed(),
                    handle.getBytesProcessed(),
                    handle.getSummary(),
                    handle.getErrorMessage(),
                    handle.getContext().getLogBufferText()
            );
            historyRepository.update(record);
        }

        notifyStateChange(taskId, executionId, oldState, finalState, handle.getSummary());

        // Clean up active task reference after keeping finished state for brief inspection
        scheduler.schedule(() -> {
            activeTasks.compute(taskId, (k, current) -> (current == handle) ? null : current);
        }, 3, TimeUnit.SECONDS);
    }

    public synchronized boolean pauseTask(String taskId) {
        TaskHandle handle = activeTasks.get(taskId);
        if (handle != null && handle.getState() == TaskState.RUNNING) {
            handle.getContext().pause();
            return true;
        }
        return false;
    }

    public synchronized boolean resumeTask(String taskId) {
        TaskHandle handle = activeTasks.get(taskId);
        if (handle != null && handle.getState() == TaskState.PAUSED) {
            handle.getContext().resume();
            return true;
        }
        return false;
    }

    public synchronized boolean cancelTask(String taskId) {
        TaskHandle handle = activeTasks.get(taskId);
        if (handle != null && handle.getState().isActive()) {
            handle.getContext().cancel();
            Future<?> f = handle.getFuture();
            if (f != null) {
                f.cancel(true); // interrupts worker thread cooperatively
            }
            return true;
        }
        return false;
    }

    /**
     * Requirement: Run-once and fixed-delay scheduling.
     */
    public synchronized void updateSchedule(TaskDefinition def) {
        String taskId = def.getId();
        unscheduleTask(taskId);

        TaskSchedule schedule = def.getSchedule();
        if (schedule != null && schedule.enabled() && schedule.type() == TaskScheduleType.FIXED_DELAY) {
            long delaySec = Math.max(1, schedule.delaySeconds());
            logger.info("Configuring fixed-delay schedule for {}: every {}s", taskId, delaySec);

            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                try {
                    logger.debug("Scheduled trigger firing for task: {}", taskId);
                    runTask(def, null);
                } catch (Exception ex) {
                    logger.error("Error executing scheduled run for {}", taskId, ex);
                }
            }, delaySec, delaySec, TimeUnit.SECONDS);

            scheduledFutures.put(taskId, future);
            notifyStateChange(taskId, "SCHEDULER", TaskState.READY, TaskState.SCHEDULED, "Scheduled every " + delaySec + "s");
        }
    }

    public synchronized void unscheduleTask(String taskId) {
        ScheduledFuture<?> future = scheduledFutures.remove(taskId);
        if (future != null) {
            future.cancel(false);
            logger.info("Cancelled schedule for task {}", taskId);
            notifyStateChange(taskId, "SCHEDULER", TaskState.SCHEDULED, TaskState.READY, "Schedule disabled");
        }
    }

    private void handlePauseStateChanged(String taskId, String executionId, boolean isPaused) {
        TaskHandle handle = activeTasks.get(taskId);
        if (handle != null) {
            TaskState old = handle.getState();
            TaskState newState = isPaused ? TaskState.PAUSED : TaskState.RUNNING;
            handle.setState(newState);
            notifyStateChange(taskId, executionId, old, newState, isPaused ? "Task paused" : "Task resumed");
        }
    }

    private void handleProgressEvent(TaskExecutionContextImpl.ProgressEvent event) {
        TaskHandle handle = activeTasks.get(event.taskId());
        if (handle != null) {
            double ratio = (event.totalWork() > 0) ? (event.workDone() / event.totalWork()) : -1.0;
            handle.setProgress(ratio);
            handle.setMessage(event.message());
        }
        for (TaskEngineListener l : listeners) {
            try {
                l.onTaskProgressUpdated(event.taskId(), event.executionId(), event.workDone(), event.totalWork(), event.message());
            } catch (Exception ex) {
                logger.warn("Listener error on progress update", ex);
            }
        }
    }

    private void notifyStateChange(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
        for (TaskEngineListener l : listeners) {
            try {
                l.onTaskStateChanged(taskId, executionId, oldState, newState, message);
            } catch (Exception ex) {
                logger.warn("Listener error on state change", ex);
            }
        }
    }

    private void notifyLog(String taskId, String executionId, String line) {
        for (TaskEngineListener l : listeners) {
            try {
                l.onTaskLogAppended(taskId, executionId, line);
            } catch (Exception ex) {
                logger.warn("Listener error on log append", ex);
            }
        }
    }

    /**
     * Requirement: Graceful shutdown that cancels work and closes resources.
     */
    public synchronized void shutdown() {
        if (isShuttingDown) return;
        isShuttingDown = true;
        logger.info("Shutting down TaskEngine...");

        // Cancel all scheduled tasks
        for (ScheduledFuture<?> sf : scheduledFutures.values()) {
            sf.cancel(true);
        }
        scheduledFutures.clear();

        // Cancel active tasks cooperatively
        for (TaskHandle handle : activeTasks.values()) {
            if (handle.getState().isActive()) {
                handle.getContext().cancel();
                if (handle.getFuture() != null) {
                    handle.getFuture().cancel(true);
                }
            }
        }

        scheduler.shutdown();
        workerPool.shutdown();

        try {
            if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            workerPool.shutdownNow();
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        logger.info("TaskEngine shutdown complete.");
    }
}

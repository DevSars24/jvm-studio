package com.jvmstudio.plugin.api;

import java.util.Map;

/**
 * Execution context provided by the JVM Studio Task Engine to a running task.
 * Enables cooperative cancellation, pausing, progress reporting, and logging
 * without tying the plugin to any UI or threading framework.
 */
public interface TaskContext {

    /**
     * Report progress towards completion.
     *
     * @param workDone  amount of work completed
     * @param totalWork total estimated work; use &lt;= 0 for indeterminate progress
     */
    void updateProgress(double workDone, double totalWork);

    /**
     * Report an indeterminate progress state.
     */
    default void updateIndeterminate() {
        updateProgress(-1.0, -1.0);
    }

    /**
     * Update the short status message displayed on the task card.
     */
    void updateMessage(String message);

    /**
     * Append an informational log entry to the task's execution log.
     */
    void log(String message);

    /**
     * Append a warning log entry.
     */
    void logWarn(String message);

    /**
     * Append an error log entry.
     */
    void logError(String message, Throwable throwable);

    /**
     * Cooperative cancellation checkpoint.
     * Plugins should call this periodically (e.g. inside loops).
     *
     * @throws InterruptedException if the task has been requested to cancel
     */
    void checkCancellation() throws InterruptedException;

    /**
     * Cooperative checkpoint that handles both pause and cancellation.
     * If the task is currently paused, this method blocks cooperatively until resumed or cancelled.
     *
     * @throws InterruptedException if the task was cancelled while paused or running
     */
    void checkPauseAndCancel() throws InterruptedException;

    /**
     * Check if cancellation has been requested without throwing an exception.
     */
    boolean isCancelled();

    /**
     * Check if the task is currently in a paused state.
     */
    boolean isPaused();

    /**
     * Store arbitrary intermediate or final key-value metadata.
     */
    void putResultData(String key, Object value);

    /**
     * Retrieve stored metadata.
     */
    Object getResultData(String key);

    /**
     * Read an unmodifiable view of all collected result data.
     */
    Map<String, Object> getAllResultData();
}

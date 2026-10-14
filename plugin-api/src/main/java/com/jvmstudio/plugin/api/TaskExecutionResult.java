package com.jvmstudio.plugin.api;

import java.util.Collections;
import java.util.Map;

/**
 * Encapsulates the final summary and statistics of a completed task execution.
 */
public record TaskExecutionResult(
        TaskExecutionStatus status,
        long itemsProcessed,
        long bytesProcessed,
        String summary,
        Map<String, Object> details,
        long durationMs,
        String errorMessage
) {
    public TaskExecutionResult {
        details = details != null ? Collections.unmodifiableMap(details) : Collections.emptyMap();
    }

    public static TaskExecutionResult success(long items, long bytes, String summary, Map<String, Object> details, long durationMs) {
        return new TaskExecutionResult(TaskExecutionStatus.SUCCESS, items, bytes, summary, details, durationMs, null);
    }

    public static TaskExecutionResult cancelled(long items, long bytes, String summary, long durationMs) {
        return new TaskExecutionResult(TaskExecutionStatus.CANCELLED, items, bytes, summary, Collections.emptyMap(), durationMs, "Cancelled by user");
    }

    public static TaskExecutionResult failed(String errorMessage, Throwable error, long durationMs) {
        String msg = errorMessage != null ? errorMessage : (error != null ? error.getMessage() : "Unknown execution failure");
        return new TaskExecutionResult(TaskExecutionStatus.FAILED, 0, 0, "Execution failed: " + msg, Collections.emptyMap(), durationMs, msg);
    }
}

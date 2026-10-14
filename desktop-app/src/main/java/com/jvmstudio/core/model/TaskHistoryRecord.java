package com.jvmstudio.core.model;

import java.time.Instant;

/**
 * Persisted record representing an execution of a task.
 */
public record TaskHistoryRecord(
        String executionId,
        String taskId,
        String taskName,
        String category,
        Instant startTime,
        Instant endTime,
        long durationMs,
        TaskState state,
        long itemsProcessed,
        long bytesProcessed,
        String summary,
        String errorMessage,
        String logSnippet
) {
    public static TaskHistoryRecord createRunning(String executionId, String taskId, String taskName, String category, Instant startTime) {
        return new TaskHistoryRecord(
                executionId,
                taskId,
                taskName,
                category,
                startTime,
                null,
                0,
                TaskState.RUNNING,
                0,
                0,
                "In progress...",
                null,
                ""
        );
    }
}

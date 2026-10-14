package com.jvmstudio.core.engine;

import com.jvmstudio.core.model.TaskState;

/**
 * Observer interface for task engine events (state transitions, progress, and logs).
 */
public interface TaskEngineListener {
    void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message);
    void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message);
    void onTaskLogAppended(String taskId, String executionId, String logLine);
}

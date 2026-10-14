package com.jvmstudio.core.engine;

import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskState;

import java.time.Instant;
import java.util.concurrent.Future;

/**
 * Handle tracking the live execution state of an active task instance.
 */
public class TaskHandle {
    private final String executionId;
    private final TaskDefinition definition;
    private final TaskExecutionContextImpl context;
    private final Instant startTime;

    private volatile TaskState state;
    private volatile Future<?> future;
    private volatile double progress = 0.0;
    private volatile String message = "Starting...";
    private volatile long itemsProcessed = 0;
    private volatile long bytesProcessed = 0;
    private volatile String summary = "";
    private volatile String errorMessage = null;

    public TaskHandle(String executionId, TaskDefinition definition, TaskExecutionContextImpl context, Instant startTime) {
        this.executionId = executionId;
        this.definition = definition;
        this.context = context;
        this.startTime = startTime;
        this.state = TaskState.RUNNING;
    }

    public String getExecutionId() {
        return executionId;
    }

    public TaskDefinition getDefinition() {
        return definition;
    }

    public String getTaskId() {
        return definition.getId();
    }

    public String getTaskName() {
        return definition.getName();
    }

    public TaskExecutionContextImpl getContext() {
        return context;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public TaskState getState() {
        return state;
    }

    public synchronized void setState(TaskState state) {
        this.state = state;
    }

    public Future<?> getFuture() {
        return future;
    }

    public void setFuture(Future<?> future) {
        this.future = future;
    }

    public double getProgress() {
        return progress;
    }

    public void setProgress(double progress) {
        this.progress = progress;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getItemsProcessed() {
        return itemsProcessed;
    }

    public void setItemsProcessed(long itemsProcessed) {
        this.itemsProcessed = itemsProcessed;
    }

    public long getBytesProcessed() {
        return bytesProcessed;
    }

    public void setBytesProcessed(long bytesProcessed) {
        this.bytesProcessed = bytesProcessed;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public long getElapsedDurationMs() {
        return System.currentTimeMillis() - startTime.toEpochMilli();
    }
}

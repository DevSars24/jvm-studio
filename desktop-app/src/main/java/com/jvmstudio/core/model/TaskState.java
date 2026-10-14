package com.jvmstudio.core.model;

/**
 * State lifecycle of a task execution.
 */
public enum TaskState {
    READY,
    SCHEDULED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == RUNNING || this == PAUSED;
    }
}

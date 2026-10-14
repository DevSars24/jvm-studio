package com.jvmstudio.core.model;

/**
 * Types of task scheduling supported by the engine.
 */
public enum TaskScheduleType {
    MANUAL("Run once immediately"),
    FIXED_DELAY("Repeat with fixed delay");

    private final String label;

    TaskScheduleType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}

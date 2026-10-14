package com.jvmstudio.plugin.api;

import java.util.Collections;
import java.util.List;

/**
 * Descriptor defining a configurable parameter for a task plugin.
 * Used by the UI to dynamically build form inputs and validate values.
 */
public record TaskParameterDescriptor(
        String name,
        String label,
        TaskParameterType type,
        Object defaultValue,
        String description,
        boolean required,
        List<String> choices
) {
    public TaskParameterDescriptor {
        choices = choices != null ? Collections.unmodifiableList(choices) : Collections.emptyList();
    }

    public static TaskParameterDescriptor of(String name, String label, TaskParameterType type, Object defaultValue, String description) {
        return new TaskParameterDescriptor(name, label, type, defaultValue, description, false, Collections.emptyList());
    }

    public static TaskParameterDescriptor ofRequired(String name, String label, TaskParameterType type, Object defaultValue, String description) {
        return new TaskParameterDescriptor(name, label, type, defaultValue, description, true, Collections.emptyList());
    }

    public static TaskParameterDescriptor ofChoice(String name, String label, String defaultValue, String description, List<String> choices) {
        return new TaskParameterDescriptor(name, label, TaskParameterType.CHOICE, defaultValue, description, true, choices);
    }
}

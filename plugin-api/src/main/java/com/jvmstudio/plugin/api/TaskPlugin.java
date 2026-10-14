package com.jvmstudio.plugin.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fundamental interface for tasks loaded either natively or dynamically from a plugin JAR.
 * Implementations should also be annotated with {@link TaskInfo}.
 */
public interface TaskPlugin {

    /**
     * Executes the task logic on a background worker thread.
     * Must periodically invoke {@link TaskContext#checkPauseAndCancel()} to support
     * cooperative pause and cancellation.
     *
     * @param context    the execution context for reporting progress, logs, and checkpoints
     * @param parameters the user-supplied configuration parameters
     * @return the execution summary and final outcome
     * @throws Exception if an unrecoverable failure occurs
     */
    TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception;

    /**
     * Returns the list of configurable parameters accepted by this task.
     * The UI renders controls dynamically based on these descriptors.
     */
    List<TaskParameterDescriptor> getParameterDescriptors();

    /**
     * Returns default values for each parameter.
     */
    default Map<String, Object> getDefaultParameters() {
        Map<String, Object> defaults = new HashMap<>();
        for (TaskParameterDescriptor desc : getParameterDescriptors()) {
            if (desc.defaultValue() != null) {
                defaults.put(desc.name(), desc.defaultValue());
            }
        }
        return defaults;
    }

    /**
     * Validates configuration parameters before execution begins.
     *
     * @throws IllegalArgumentException if required parameters are missing or invalid
     */
    default void validateParameters(Map<String, Object> parameters) throws IllegalArgumentException {
        if (parameters == null) {
            parameters = Collections.emptyMap();
        }
        for (TaskParameterDescriptor desc : getParameterDescriptors()) {
            if (desc.required()) {
                Object val = parameters.get(desc.name());
                if (val == null || (val instanceof String s && s.isBlank())) {
                    throw new IllegalArgumentException("Parameter '" + desc.label() + "' is required.");
                }
            }
        }
    }
}

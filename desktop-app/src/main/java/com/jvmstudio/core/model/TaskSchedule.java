package com.jvmstudio.core.model;

/**
 * Schedule definition for a task.
 */
public record TaskSchedule(
        TaskScheduleType type,
        long delaySeconds,
        boolean enabled
) {
    public static TaskSchedule once() {
        return new TaskSchedule(TaskScheduleType.MANUAL, 0, false);
    }

    public static TaskSchedule fixedDelay(long delaySeconds) {
        return new TaskSchedule(TaskScheduleType.FIXED_DELAY, Math.max(1, delaySeconds), true);
    }
}

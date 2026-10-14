package com.jvmstudio.core.model;

/**
 * Application-wide settings for JVM Studio.
 */
public record AppSettings(
        int threadPoolSize,
        int jvmSamplingIntervalMs,
        CollisionPolicy defaultCollisionPolicy,
        int historyRetentionDays,
        boolean darkTheme,
        boolean autoScrollLogs
) {
    public static AppSettings defaultSettings() {
        return new AppSettings(
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                1000,
                CollisionPolicy.RENAME_TIMESTAMP,
                30,
                true,
                true
        );
    }
}

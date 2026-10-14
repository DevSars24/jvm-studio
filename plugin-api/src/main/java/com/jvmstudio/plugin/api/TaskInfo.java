package com.jvmstudio.plugin.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Metadata annotation placed on {@link TaskPlugin} implementations.
 * Inspected via reflection by the JVM Studio runtime.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface TaskInfo {
    /**
     * Unique identifier of the task/plugin (e.g. "word-counter").
     */
    String id();

    /**
     * Display name shown in UI cards and lists.
     */
    String name();

    /**
     * Plugin version (semantic versioning, e.g. "1.0.0").
     */
    String version() default "1.0.0";

    /**
     * Author or organization.
     */
    String author() default "JVM Studio";

    /**
     * High-level functional description.
     */
    String description() default "";

    /**
     * Target Plugin API version this plugin was compiled against (e.g. "1.0").
     */
    String apiVersion() default PluginConstants.CURRENT_API_VERSION;

    /**
     * Category for grouping (e.g. "Analysis", "IO", "Diagnostic").
     */
    String category() default "General";
}

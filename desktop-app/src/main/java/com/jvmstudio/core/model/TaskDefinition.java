package com.jvmstudio.core.model;

import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Definition of a registered task (either built-in or loaded from a plugin JAR).
 */
public class TaskDefinition {
    private final String id;
    private final String name;
    private final String version;
    private final String author;
    private final String description;
    private final String category;
    private final String apiVersion;
    private final TaskPlugin plugin;
    private final Path jarPath;
    private final Map<String, Object> configuredParameters = new HashMap<>();
    private TaskSchedule schedule;

    public TaskDefinition(String id, String name, String version, String author,
                          String description, String category, String apiVersion,
                          TaskPlugin plugin, Path jarPath) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.version = version != null ? version : "1.0.0";
        this.author = author != null ? author : "Built-in";
        this.description = description != null ? description : "";
        this.category = category != null ? category : "General";
        this.apiVersion = apiVersion != null ? apiVersion : "1.0";
        this.plugin = Objects.requireNonNull(plugin, "plugin must not be null");
        this.jarPath = jarPath;
        this.schedule = TaskSchedule.once();
        this.configuredParameters.putAll(plugin.getDefaultParameters());
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getVersion() {
        return version;
    }

    public String getAuthor() {
        return author;
    }

    public String getDescription() {
        return description;
    }

    public String getCategory() {
        return category;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public TaskPlugin getPlugin() {
        return plugin;
    }

    public Path getJarPath() {
        return jarPath;
    }

    public boolean isBuiltIn() {
        return jarPath == null;
    }

    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return plugin.getParameterDescriptors();
    }

    public synchronized Map<String, Object> getConfiguredParameters() {
        return Collections.unmodifiableMap(new HashMap<>(configuredParameters));
    }

    public synchronized void setParameters(Map<String, Object> params) {
        if (params != null) {
            configuredParameters.clear();
            configuredParameters.putAll(plugin.getDefaultParameters());
            configuredParameters.putAll(params);
        }
    }

    public synchronized void setParameter(String key, Object value) {
        configuredParameters.put(key, value);
    }

    public synchronized TaskSchedule getSchedule() {
        return schedule;
    }

    public synchronized void setSchedule(TaskSchedule schedule) {
        this.schedule = schedule != null ? schedule : TaskSchedule.once();
    }
}

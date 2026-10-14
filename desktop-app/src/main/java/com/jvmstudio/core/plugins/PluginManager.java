package com.jvmstudio.core.plugins;

import com.jvmstudio.core.db.PluginRegistryRepository;
import com.jvmstudio.core.engine.TaskEngine;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.plugin.api.PluginConstants;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Manages the dynamic loading, reflection inspection, validation,
 * execution integration, and unloading of external task plugin JARs.
 */
public class PluginManager {
    private static final Logger logger = LoggerFactory.getLogger(PluginManager.class);

    public record LoadedPlugin(
            String id,
            TaskDefinition taskDefinition,
            URLClassLoader classLoader,
            Path jarPath,
            TaskInfo taskInfo,
            Class<?> pluginClass
    ) {}

    private final PluginRegistryRepository registryRepository;
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();
    private final List<PluginLifecycleListener> listeners = new ArrayList<>();

    public interface PluginLifecycleListener {
        void onPluginLoaded(TaskDefinition taskDef);
        void onPluginUnloaded(String pluginId);
    }

    public PluginManager(PluginRegistryRepository registryRepository) {
        this.registryRepository = registryRepository;
    }

    public void addListener(PluginLifecycleListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public List<LoadedPlugin> getLoadedPlugins() {
        return new ArrayList<>(loadedPlugins.values());
    }

    public LoadedPlugin getPlugin(String pluginId) {
        return loadedPlugins.get(pluginId);
    }

    public boolean isLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    /**
     * Dynamically loads a plugin JAR using URLClassLoader and ServiceLoader/reflection.
     *
     * @param jarPath the path to the plugin JAR file
     * @return the registered TaskDefinition
     * @throws Exception if JAR is invalid, missing metadata, or incompatible
     */
    public synchronized TaskDefinition loadPluginJar(Path jarPath) throws Exception {
        Objects.requireNonNull(jarPath, "jarPath must not be null");

        if (!Files.exists(jarPath) || !Files.isRegularFile(jarPath)) {
            throw new IllegalArgumentException("Plugin file does not exist or is not a regular file: " + jarPath);
        }

        if (!jarPath.getFileName().toString().toLowerCase().endsWith(".jar")) {
            throw new IllegalArgumentException("File must be a .jar archive: " + jarPath.getFileName());
        }

        // Validate JAR file integrity
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            if (jar.size() == 0) {
                throw new IllegalArgumentException("Plugin JAR archive is empty: " + jarPath.getFileName());
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Corrupt or invalid JAR archive: " + e.getMessage(), e);
        }

        // Create isolated child classloader with current classloader as parent
        // This ensures plugin-api interfaces are shared from the parent
        URL jarUrl = jarPath.toUri().toURL();
        URLClassLoader pluginClassLoader = new URLClassLoader(
                new URL[]{jarUrl},
                this.getClass().getClassLoader()
        );

        TaskPlugin discoveredInstance = null;
        Class<?> pluginClass = null;

        // 1. Try ServiceLoader discovery
        ServiceLoader<TaskPlugin> sl = ServiceLoader.load(TaskPlugin.class, pluginClassLoader);
        for (TaskPlugin plugin : sl) {
            discoveredInstance = plugin;
            pluginClass = plugin.getClass();
            break;
        }

        // 2. Fallback: scan JAR classes for TaskPlugin implementation and @TaskInfo
        if (discoveredInstance == null) {
            pluginClass = findPluginClassByScanning(jarPath, pluginClassLoader);
            if (pluginClass != null) {
                Object obj = pluginClass.getDeclaredConstructor().newInstance();
                discoveredInstance = (TaskPlugin) obj;
            }
        }

        if (discoveredInstance == null) {
            pluginClassLoader.close();
            throw new IllegalArgumentException("No implementation of " + TaskPlugin.class.getName() +
                    " found in JAR. Ensure META-INF/services/com.jvmstudio.plugin.api.TaskPlugin exists or class is public with default constructor.");
        }

        // 3. Inspect @TaskInfo annotation via Reflection
        TaskInfo info = pluginClass.getAnnotation(TaskInfo.class);
        if (info == null) {
            pluginClassLoader.close();
            throw new IllegalArgumentException("Plugin class " + pluginClass.getName() + " is missing required @TaskInfo annotation.");
        }

        // 4. Validate API version compatibility
        if (!PluginConstants.isCompatible(info.apiVersion())) {
            pluginClassLoader.close();
            throw new IllegalArgumentException(String.format(
                    "Plugin '%s' requires API version '%s', but this host supports version '%s'.",
                    info.name(), info.apiVersion(), PluginConstants.CURRENT_API_VERSION));
        }

        // 5. Check duplicate plugin ID
        if (loadedPlugins.containsKey(info.id())) {
            pluginClassLoader.close();
            throw new IllegalArgumentException("A plugin with ID '" + info.id() + "' is already loaded.");
        }

        TaskDefinition taskDef = new TaskDefinition(
                info.id(),
                info.name(),
                info.version(),
                info.author(),
                info.description(),
                info.category(),
                info.apiVersion(),
                discoveredInstance,
                jarPath
        );

        LoadedPlugin loaded = new LoadedPlugin(info.id(), taskDef, pluginClassLoader, jarPath, info, pluginClass);
        loadedPlugins.put(info.id(), loaded);

        // Persist in database
        if (registryRepository != null) {
            registryRepository.registerPlugin(info.id(), info.name(), info.version(), info.author(), info.apiVersion(), jarPath);
        }

        logger.info("Successfully loaded plugin: '{}' v{} [id={}] from {}", info.name(), info.version(), info.id(), jarPath.getFileName());

        for (PluginLifecycleListener l : listeners) {
            l.onPluginLoaded(taskDef);
        }

        return taskDef;
    }

    private Class<?> findPluginClassByScanning(Path jarPath, URLClassLoader classLoader) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("$")) {
                    String className = name.substring(0, name.length() - 6).replace('/', '.');
                    try {
                        Class<?> clazz = Class.forName(className, false, classLoader);
                        if (TaskPlugin.class.isAssignableFrom(clazz) && clazz.isAnnotationPresent(TaskInfo.class)) {
                            return clazz;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (IOException ignored) {}
        return null;
    }

    /**
     * Requirement: Allow removing a plugin only when no task is using it.
     * Close its classloader when appropriate, and explain that closing it does not guarantee immediate class unloading.
     */
    public synchronized String unloadPlugin(String pluginId, TaskEngine taskEngine) throws Exception {
        LoadedPlugin loaded = loadedPlugins.get(pluginId);
        if (loaded == null) {
            throw new IllegalArgumentException("Plugin not found: " + pluginId);
        }

        if (taskEngine != null && taskEngine.isTaskRunningOrPaused(pluginId)) {
            throw new IllegalStateException("Cannot unload plugin '" + loaded.taskInfo().name() + "': task is currently active or paused. Stop or cancel it first.");
        }

        if (taskEngine != null) {
            taskEngine.unscheduleTask(pluginId);
        }

        loadedPlugins.remove(pluginId);

        if (registryRepository != null) {
            registryRepository.unregisterPlugin(pluginId);
        }

        try {
            loaded.classLoader().close();
            logger.info("Closed URLClassLoader for plugin {}", pluginId);
        } catch (IOException e) {
            logger.warn("Error closing URLClassLoader for plugin {}", pluginId, e);
        }

        for (PluginLifecycleListener l : listeners) {
            l.onPluginUnloaded(pluginId);
        }

        return "Plugin '" + loaded.taskInfo().name() + "' was successfully unloaded and its ClassLoader closed. " +
                "Note: JVM Garbage Collector will reclaim class metadata from Metaspace once all heap references are cleared.";
    }

    public synchronized void closeAll() {
        for (LoadedPlugin p : loadedPlugins.values()) {
            try {
                p.classLoader().close();
            } catch (IOException ignored) {}
        }
        loadedPlugins.clear();
    }
}

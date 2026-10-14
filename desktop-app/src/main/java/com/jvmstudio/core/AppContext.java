package com.jvmstudio.core;

import com.jvmstudio.core.db.DatabaseManager;
import com.jvmstudio.core.db.PluginRegistryRepository;
import com.jvmstudio.core.db.SettingsRepository;
import com.jvmstudio.core.db.TaskConfigRepository;
import com.jvmstudio.core.db.TaskHistoryRepository;
import com.jvmstudio.core.engine.TaskEngine;
import com.jvmstudio.core.jvm.JvmMonitorService;
import com.jvmstudio.core.model.AppSettings;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.plugins.PluginManager;
import com.jvmstudio.core.tasks.FileBackupTask;
import com.jvmstudio.core.tasks.FolderAnalyzerTask;
import com.jvmstudio.core.tasks.MemoryAllocationDemoTask;
import com.jvmstudio.core.tasks.Sha256VerifierTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Central application context managing lifecycle, dependency wiring,
 * persistence, task registry, and clean application shutdown.
 */
public class AppContext implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(AppContext.class);

    private final DatabaseManager databaseManager;
    private final TaskHistoryRepository historyRepository;
    private final TaskConfigRepository configRepository;
    private final PluginRegistryRepository pluginRegistryRepository;
    private final SettingsRepository settingsRepository;

    private AppSettings settings;
    private final TaskEngine taskEngine;
    private final PluginManager pluginManager;
    private final JvmMonitorService jvmMonitorService;

    private final Map<String, TaskDefinition> taskDefinitions = new LinkedHashMap<>();

    public AppContext() {
        this(DatabaseManager.resolveDefaultDbPath());
    }

    public AppContext(Path dbPath) {
        logger.info("Initializing JVM Studio AppContext with database: {}", dbPath);
        this.databaseManager = new DatabaseManager(dbPath);
        this.historyRepository = new TaskHistoryRepository(databaseManager);
        this.configRepository = new TaskConfigRepository(databaseManager);
        this.pluginRegistryRepository = new PluginRegistryRepository(databaseManager);
        this.settingsRepository = new SettingsRepository(databaseManager);

        this.settings = settingsRepository.loadSettings();

        this.taskEngine = new TaskEngine(settings.threadPoolSize(), historyRepository);
        this.pluginManager = new PluginManager(pluginRegistryRepository);
        this.jvmMonitorService = new JvmMonitorService(settings.jvmSamplingIntervalMs());

        // Register built-in tasks
        registerBuiltInTasks();

        // Restore saved configs
        restoreTaskConfigurations();

        // Listen for new plugins
        pluginManager.addListener(new PluginManager.PluginLifecycleListener() {
            @Override
            public void onPluginLoaded(TaskDefinition taskDef) {
                registerTaskDefinition(taskDef);
            }

            @Override
            public void onPluginUnloaded(String pluginId) {
                unregisterTaskDefinition(pluginId);
            }
        });

        // Restore saved plugins if files exist
        restoreSavedPlugins();
    }

    private void registerBuiltInTasks() {
        registerBuiltIn(new FileBackupTask(), "file-backup", "File & Folder Backup Engine", "1.0.0", "JVM Studio Core",
                "Recursive folder backup with collision handling, loop prevention, and chunked progress.", "File Operations");

        registerBuiltIn(new FolderAnalyzerTask(), "folder-analyzer", "Disk & Folder Storage Analyzer", "1.0.0", "JVM Studio Core",
                "Deep disk scanner detailing volume, extension distribution, and largest files.", "Analysis");

        registerBuiltIn(new Sha256VerifierTask(), "sha256-verifier", "SHA-256 Cryptographic Verifier", "1.0.0", "JVM Studio Core",
                "Computes cryptographic SHA-256 hashes with real streaming and table copy.", "Security");

        registerBuiltIn(new MemoryAllocationDemoTask(), "memory-allocation-demo", "JVM Heap Allocation & GC Demo", "1.0.0", "JVM Studio Core",
                "Interactive educational demonstration of heap allocation, GC roots, reference clearing, and System.gc().", "Educational Lab");
    }

    private void registerBuiltIn(com.jvmstudio.plugin.api.TaskPlugin plugin, String id, String name,
                                 String version, String author, String description, String category) {
        TaskDefinition def = new TaskDefinition(id, name, version, author, description, category, "1.0", plugin, null);
        taskDefinitions.put(id, def);
    }

    public synchronized void registerTaskDefinition(TaskDefinition def) {
        taskDefinitions.put(def.getId(), def);
        logger.info("Registered task definition: {} [{}]", def.getName(), def.getId());
    }

    public synchronized void unregisterTaskDefinition(String taskId) {
        taskDefinitions.remove(taskId);
        logger.info("Unregistered task definition: {}", taskId);
    }

    public synchronized List<TaskDefinition> getAllTaskDefinitions() {
        return new ArrayList<>(taskDefinitions.values());
    }

    public synchronized TaskDefinition getTaskDefinition(String taskId) {
        return taskDefinitions.get(taskId);
    }

    private void restoreTaskConfigurations() {
        for (TaskDefinition def : taskDefinitions.values()) {
            TaskConfigRepository.PersistedTaskConfig persisted = configRepository.findConfig(def.getId());
            if (persisted != null) {
                if (persisted.stringParams() != null && !persisted.stringParams().isEmpty()) {
                    for (Map.Entry<String, String> e : persisted.stringParams().entrySet()) {
                        def.setParameter(e.getKey(), e.getValue());
                    }
                }
                if (persisted.schedule() != null) {
                    def.setSchedule(persisted.schedule());
                    if (persisted.schedule().enabled()) {
                        taskEngine.updateSchedule(def);
                    }
                }
            }
        }
    }

    /**
     * Requirement: Validate saved plugin paths before offering to reload them.
     * Do not automatically execute plugin code.
     */
    private void restoreSavedPlugins() {
        List<PluginRegistryRepository.PluginEntry> saved = pluginRegistryRepository.listSavedPlugins();
        for (PluginRegistryRepository.PluginEntry entry : saved) {
            if (entry.fileExists()) {
                try {
                    logger.info("Restoring previously registered plugin from: {}", entry.jarPath());
                    pluginManager.loadPluginJar(entry.jarPath());
                } catch (Exception e) {
                    logger.warn("Could not reload saved plugin from {}: {}", entry.jarPath(), e.getMessage());
                }
            } else {
                logger.warn("Saved plugin JAR not found at {}, skipping: {}", entry.jarPath(), entry.pluginName());
            }
        }
    }

    public void saveTaskConfig(TaskDefinition def) {
        configRepository.saveConfig(def.getId(), def.getConfiguredParameters(), def.getSchedule());
        taskEngine.updateSchedule(def);
    }

    public void updateSettings(AppSettings newSettings) {
        this.settings = newSettings;
        settingsRepository.saveSettings(newSettings);
        taskEngine.setPoolSize(newSettings.threadPoolSize());
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public TaskHistoryRepository getHistoryRepository() {
        return historyRepository;
    }

    public TaskConfigRepository getConfigRepository() {
        return configRepository;
    }

    public PluginRegistryRepository getPluginRegistryRepository() {
        return pluginRegistryRepository;
    }

    public SettingsRepository getSettingsRepository() {
        return settingsRepository;
    }

    public AppSettings getSettings() {
        return settings;
    }

    public TaskEngine getTaskEngine() {
        return taskEngine;
    }

    public PluginManager getPluginManager() {
        return pluginManager;
    }

    public JvmMonitorService getJvmMonitorService() {
        return jvmMonitorService;
    }

    @Override
    public synchronized void close() {
        logger.info("Shutting down JVM Studio AppContext...");
        try {
            taskEngine.shutdown();
        } catch (Exception e) {
            logger.warn("Error during task engine shutdown", e);
        }

        try {
            jvmMonitorService.stop();
        } catch (Exception e) {
            logger.warn("Error stopping JVM monitor", e);
        }

        try {
            pluginManager.closeAll();
        } catch (Exception e) {
            logger.warn("Error closing plugins", e);
        }

        try {
            databaseManager.close();
        } catch (Exception e) {
            logger.warn("Error closing database", e);
        }
        logger.info("JVM Studio AppContext closed cleanly.");
    }
}

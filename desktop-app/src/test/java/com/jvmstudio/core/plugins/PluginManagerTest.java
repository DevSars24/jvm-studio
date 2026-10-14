package com.jvmstudio.core.plugins;

import com.jvmstudio.core.db.DatabaseManager;
import com.jvmstudio.core.db.PluginRegistryRepository;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.plugin.api.PluginConstants;
import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class PluginManagerTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private PluginRegistryRepository registryRepo;
    private PluginManager pluginManager;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(tempDir.resolve("test_plugins.db"));
        registryRepo = new PluginRegistryRepository(dbManager);
        pluginManager = new PluginManager(registryRepo);
    }

    @AfterEach
    void tearDown() {
        pluginManager.closeAll();
        dbManager.close();
    }

    @Test
    void testRejectNonExistentJar() {
        assertThrows(IllegalArgumentException.class, () -> {
            pluginManager.loadPluginJar(tempDir.resolve("does_not_exist.jar"));
        });
    }

    @Test
    void testRejectInvalidOrEmptyJar() throws IOException {
        Path emptyFile = tempDir.resolve("empty.jar");
        Files.createFile(emptyFile);

        assertThrows(IllegalArgumentException.class, () -> {
            pluginManager.loadPluginJar(emptyFile);
        });
    }

    @Test
    void testRejectNonJarExtension() throws IOException {
        Path textFile = tempDir.resolve("plugin.txt");
        Files.writeString(textFile, "some text");

        assertThrows(IllegalArgumentException.class, () -> {
            pluginManager.loadPluginJar(textFile);
        });
    }

    @Test
    void testLoadValidDynamicPluginJar() throws Exception {
        // Build a dynamic JAR in tempDir with a valid plugin class + SPI service descriptor
        Path pluginJar = createTestPluginJar("dynamic-test", MockTestPlugin.class);

        TaskDefinition def = pluginManager.loadPluginJar(pluginJar);
        assertNotNull(def);
        assertEquals("test-dynamic-plugin", def.getId());
        assertEquals("Dynamic Test Plugin", def.getName());
        assertEquals("1.0.0", def.getVersion());

        assertTrue(pluginManager.isLoaded("test-dynamic-plugin"));

        // Test reflection inspection
        PluginManager.LoadedPlugin loaded = pluginManager.getPlugin("test-dynamic-plugin");
        assertNotNull(loaded);
        assertEquals(pluginJar, loaded.jarPath());

        // Test execution
        TaskExecutionResult result = def.getPlugin().execute(new TaskContext() {
            @Override public void updateProgress(double workDone, double totalWork) {}
            @Override public void updateMessage(String message) {}
            @Override public void log(String message) {}
            @Override public void logWarn(String message) {}
            @Override public void logError(String message, Throwable throwable) {}
            @Override public void checkCancellation() {}
            @Override public void checkPauseAndCancel() {}
            @Override public boolean isCancelled() { return false; }
            @Override public boolean isPaused() { return false; }
            @Override public void putResultData(String key, Object value) {}
            @Override public Object getResultData(String key) { return null; }
            @Override public Map<String, Object> getAllResultData() { return Collections.emptyMap(); }
        }, Collections.emptyMap());

        assertNotNull(result);
        assertEquals("Dynamic plugin executed successfully", result.summary());

        // Test unload
        String unloadMsg = pluginManager.unloadPlugin("test-dynamic-plugin", null);
        assertNotNull(unloadMsg);
        assertFalse(pluginManager.isLoaded("test-dynamic-plugin"));
    }

    @Test
    void testRejectIncompatibleApiVersion() throws Exception {
        // Incompatible version "99.0"
        Path incompatibleJar = createTestPluginJar("incompatible-plugin", IncompatibleApiMockPlugin.class);

        Exception ex = assertThrows(IllegalArgumentException.class, () -> {
            pluginManager.loadPluginJar(incompatibleJar);
        });
        assertTrue(ex.getMessage().contains("requires API version '99.0'"));
    }

    @Test
    void testRejectDuplicatePluginId() throws Exception {
        Path jar1 = createTestPluginJar("duplicate-1", MockTestPlugin.class);
        pluginManager.loadPluginJar(jar1);

        Path jar2 = createTestPluginJar("duplicate-2", MockTestPlugin.class);
        Exception ex = assertThrows(IllegalArgumentException.class, () -> {
            pluginManager.loadPluginJar(jar2);
        });
        assertTrue(ex.getMessage().contains("already loaded"));
    }

    /**
     * Helper to package a class dynamically into a JAR file for testing.
     */
    private Path createTestPluginJar(String jarName, Class<? extends TaskPlugin> pluginClass) throws Exception {
        Path jarPath = tempDir.resolve("test-plugin-" + jarName + ".jar");

        String classAsPath = pluginClass.getName().replace('.', '/') + ".class";

        byte[] classBytes;
        try (InputStream in = pluginClass.getClassLoader().getResourceAsStream(classAsPath)) {
            assertNotNull(in, "Could not find class resource for " + classAsPath);
            classBytes = in.readAllBytes();
        }

        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(java.util.jar.Attributes.Name.MANIFEST_VERSION, "1.0");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()), manifest)) {
            // Add class entry
            JarEntry classEntry = new JarEntry(classAsPath);
            jos.putNextEntry(classEntry);
            jos.write(classBytes);
            jos.closeEntry();

            // Add SPI ServiceLoader provider entry
            JarEntry serviceEntry = new JarEntry("META-INF/services/com.jvmstudio.plugin.api.TaskPlugin");
            jos.putNextEntry(serviceEntry);
            jos.write(pluginClass.getName().getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return jarPath;
    }

    @TaskInfo(
            id = "test-dynamic-plugin",
            name = "Dynamic Test Plugin",
            version = "1.0.0",
            author = "Test Suite",
            description = "Mock dynamic plugin for unit test",
            apiVersion = "1.0"
    )
    public static class MockTestPlugin implements TaskPlugin {
        @Override
        public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
            return TaskExecutionResult.success(1, 1, "Dynamic plugin executed successfully", Collections.emptyMap(), 10);
        }

        @Override
        public List<TaskParameterDescriptor> getParameterDescriptors() {
            return Collections.emptyList();
        }
    }

    @TaskInfo(
            id = "incompatible-plugin",
            name = "Incompatible API Plugin",
            version = "1.0.0",
            author = "Test Suite",
            description = "Incompatible API test plugin",
            apiVersion = "99.0"
    )
    public static class IncompatibleApiMockPlugin implements TaskPlugin {
        @Override
        public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
            return TaskExecutionResult.success(0, 0, "Incompatible", Collections.emptyMap(), 0);
        }

        @Override
        public List<TaskParameterDescriptor> getParameterDescriptors() {
            return Collections.emptyList();
        }
    }
}

package com.jvmstudio.core.db;

import com.jvmstudio.core.model.CollisionPolicy;
import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskSchedule;
import com.jvmstudio.core.model.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabasePersistenceTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private TaskHistoryRepository historyRepo;
    private TaskConfigRepository configRepo;
    private PluginRegistryRepository pluginRepo;
    private SettingsRepository settingsRepo;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(tempDir.resolve("test_persistence.db"));
        historyRepo = new TaskHistoryRepository(dbManager);
        configRepo = new TaskConfigRepository(dbManager);
        pluginRepo = new PluginRegistryRepository(dbManager);
        settingsRepo = new SettingsRepository(dbManager);
    }

    @AfterEach
    void tearDown() {
        dbManager.close();
    }

    @Test
    void testSchemaVersionInitialized() throws Exception {
        try (Connection conn = dbManager.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }

    @Test
    void testInsertAndQueryHistory() {
        TaskHistoryRecord record = new TaskHistoryRecord(
                "exec-101",
                "test-task",
                "Test Task",
                "Testing",
                Instant.now().minusSeconds(10),
                Instant.now(),
                500,
                TaskState.COMPLETED,
                42,
                1024,
                "Executed successfully with 42 items",
                null,
                "[INFO] Test log line"
        );

        historyRepo.insert(record);

        List<TaskHistoryRecord> list = historyRepo.findFiltered("test-task", TaskState.COMPLETED, 10, 0);
        assertEquals(1, list.size());
        assertEquals("exec-101", list.get(0).executionId());
        assertEquals(42, list.get(0).itemsProcessed());
    }

    @Test
    void testMarkInterruptedRunsOnStartup() throws Exception {
        // Insert a record that was in RUNNING state when JVM terminated
        TaskHistoryRecord runningRecord = TaskHistoryRecord.createRunning(
                "interrupted-1", "long-task", "Long Task", "Test", Instant.now().minusSeconds(60)
        );
        historyRepo.insert(runningRecord);

        // Verify inserted as RUNNING
        List<TaskHistoryRecord> before = historyRepo.findFiltered("long-task", TaskState.RUNNING, 10, 0);
        assertEquals(1, before.size());

        // Close connection and re-instantiate DatabaseManager (simulating restart)
        dbManager.close();
        DatabaseManager restartDb = new DatabaseManager(tempDir.resolve("test_persistence.db"));
        TaskHistoryRepository restartRepo = new TaskHistoryRepository(restartDb);

        // Record should now be marked as FAILED with interrupted summary
        List<TaskHistoryRecord> after = restartRepo.findFiltered("long-task", TaskState.FAILED, 10, 0);
        assertEquals(1, after.size());
        assertTrue(after.get(0).summary().toLowerCase().contains("interrupted"));

        restartDb.close();
    }

    @Test
    void testRfc4180CsvExportEscaping() throws Exception {
        TaskHistoryRecord record = new TaskHistoryRecord(
                "csv-1",
                "task-csv",
                "Task, with comma",
                "Category",
                Instant.now(),
                Instant.now(),
                100,
                TaskState.COMPLETED,
                5,
                200,
                "Summary with \"quotes\" and\nnewline, plus comma",
                "Error, none",
                "log"
        );

        StringWriter sw = new StringWriter();
        historyRepo.exportToCsv(sw, List.of(record));
        String csv = sw.toString();

        // Must escape quotes by doubling them (""quotes"")
        assertTrue(csv.contains("\"Summary with \"\"quotes\"\" and\nnewline, plus comma\""));
        assertTrue(csv.contains("\"Task, with comma\""));
    }

    @Test
    void testTaskConfigPersistence() {
        configRepo.saveConfig("file-backup", Map.of("source", "C:/src", "collisionPolicy", "SKIP"), TaskSchedule.fixedDelay(30));

        var loaded = configRepo.findConfig("file-backup");
        assertNotNull(loaded);
        assertEquals("C:/src", loaded.stringParams().get("source"));
        assertEquals("SKIP", loaded.stringParams().get("collisionPolicy"));
        assertEquals(30, loaded.schedule().delaySeconds());
        assertTrue(loaded.schedule().enabled());
    }

    @Test
    void testPluginRegistryAndPathValidation() throws Exception {
        Path dummyJar = tempDir.resolve("dummy.jar");
        Files.createFile(dummyJar);

        pluginRepo.registerPlugin("my-plugin", "My Plugin", "1.0.0", "Author", "1.0", dummyJar);

        var list = pluginRepo.listSavedPlugins();
        assertEquals(1, list.size());
        assertTrue(list.get(0).fileExists());

        // Delete the dummy jar
        Files.delete(dummyJar);
        var listAfterDelete = pluginRepo.listSavedPlugins();
        assertEquals(1, listAfterDelete.size());
        assertFalse(listAfterDelete.get(0).fileExists(), "Registry should accurately report fileExists=false when JAR was moved or removed");
    }
}

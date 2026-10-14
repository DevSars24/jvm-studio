package com.jvmstudio.core.tasks;

import com.jvmstudio.core.engine.TaskExecutionContextImpl;
import com.jvmstudio.core.model.CollisionPolicy;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskExecutionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FileBackupTaskTest {

    @TempDir
    Path tempDir;

    private Path sourceDir;
    private Path destDir;
    private FileBackupTask backupTask;

    @BeforeEach
    void setUp() throws IOException {
        sourceDir = tempDir.resolve("source");
        destDir = tempDir.resolve("destination");
        Files.createDirectories(sourceDir);
        Files.createDirectories(destDir);

        // Populate test source files
        Files.writeString(sourceDir.resolve("file1.txt"), "Content of file 1");
        Path sub = sourceDir.resolve("subfolder");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("file2.txt"), "Content of file 2 in subfolder");

        backupTask = new FileBackupTask();
    }

    private TaskExecutionContextImpl createMockContext() {
        return new TaskExecutionContextImpl("test-backup", "exec-1", null, null, null);
    }

    @Test
    void testBasicBackupCopy() throws Exception {
        Map<String, Object> params = Map.of(
                "source", sourceDir.toString(),
                "destination", destDir.toString(),
                "collisionPolicy", CollisionPolicy.OVERWRITE.name()
        );

        TaskExecutionResult result = backupTask.execute(createMockContext(), params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        assertEquals(2, result.itemsProcessed());
        assertTrue(Files.exists(destDir.resolve("file1.txt")));
        assertTrue(Files.exists(destDir.resolve("subfolder").resolve("file2.txt")));
        assertEquals("Content of file 1", Files.readString(destDir.resolve("file1.txt")));
    }

    @Test
    void testCollisionPolicySkip() throws Exception {
        // Pre-create destination file with different content
        Files.writeString(destDir.resolve("file1.txt"), "Existing destination content");

        Map<String, Object> params = Map.of(
                "source", sourceDir.toString(),
                "destination", destDir.toString(),
                "collisionPolicy", CollisionPolicy.SKIP.name()
        );

        TaskExecutionResult result = backupTask.execute(createMockContext(), params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        // Destination content should remain unchanged
        assertEquals("Existing destination content", Files.readString(destDir.resolve("file1.txt")));
    }

    @Test
    void testCollisionPolicyOverwrite() throws Exception {
        Files.writeString(destDir.resolve("file1.txt"), "Old content");

        Map<String, Object> params = Map.of(
                "source", sourceDir.toString(),
                "destination", destDir.toString(),
                "collisionPolicy", CollisionPolicy.OVERWRITE.name()
        );

        TaskExecutionResult result = backupTask.execute(createMockContext(), params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        assertEquals("Content of file 1", Files.readString(destDir.resolve("file1.txt")));
    }

    @Test
    void testCollisionPolicyRenameTimestamp() throws Exception {
        Files.writeString(destDir.resolve("file1.txt"), "Original content to keep");

        Map<String, Object> params = Map.of(
                "source", sourceDir.toString(),
                "destination", destDir.toString(),
                "collisionPolicy", CollisionPolicy.RENAME_TIMESTAMP.name()
        );

        TaskExecutionResult result = backupTask.execute(createMockContext(), params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        assertEquals("Original content to keep", Files.readString(destDir.resolve("file1.txt")));

        // Check renamed file exists
        try (Stream<Path> stream = Files.list(destDir)) {
            boolean hasBackupRenamed = stream.anyMatch(p -> p.getFileName().toString().startsWith("file1_backup_"));
            assertTrue(hasBackupRenamed, "A timestamped backup copy of file1 should have been created");
        }
    }

    @Test
    void testLoopPreventionWhenDestinationIsInsideSource() throws Exception {
        Path nestedDest = sourceDir.resolve("nested_backup_target");
        Files.createDirectories(nestedDest);

        Map<String, Object> params = Map.of(
                "source", sourceDir.toString(),
                "destination", nestedDest.toString(),
                "collisionPolicy", CollisionPolicy.OVERWRITE.name()
        );

        TaskExecutionResult result = backupTask.execute(createMockContext(), params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        assertTrue(Files.exists(nestedDest.resolve("file1.txt")));
        // Verify it didn't endlessly recurse into itself
        assertFalse(Files.exists(nestedDest.resolve("nested_backup_target")));
    }
}

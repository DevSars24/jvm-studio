package com.jvmstudio.core.tasks;

import com.jvmstudio.core.engine.TaskExecutionContextImpl;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskExecutionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class Sha256VerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void testSha256CorrectnessAgainstKnownVector() throws Exception {
        // Standard NIST test vector: "abc"
        Path testFile = tempDir.resolve("abc.txt");
        Files.writeString(testFile, "abc");

        Sha256VerifierTask task = new Sha256VerifierTask();
        TaskExecutionContextImpl context = new TaskExecutionContextImpl("sha256-test", "exec-1", null, null, null);

        Map<String, Object> params = Map.of("target", testFile.toString());
        TaskExecutionResult result = task.execute(context, params);

        assertEquals(TaskExecutionStatus.SUCCESS, result.status());
        assertEquals(1, result.itemsProcessed());

        @SuppressWarnings("unchecked")
        List<Sha256VerifierTask.HashResultEntry> entries = (List<Sha256VerifierTask.HashResultEntry>) context.getResultData("hashEntries");
        assertNotNull(entries);
        assertEquals(1, entries.size());

        String expectedHash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        assertEquals(expectedHash, entries.get(0).sha256());
    }
}

package com.jvmstudio.core.engine;

import com.jvmstudio.core.db.DatabaseManager;
import com.jvmstudio.core.db.TaskHistoryRepository;
import com.jvmstudio.core.model.TaskDefinition;
import com.jvmstudio.core.model.TaskState;
import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class TaskEngineTest {

    @TempDir
    Path tempDir;

    private DatabaseManager dbManager;
    private TaskHistoryRepository historyRepo;
    private TaskEngine engine;

    @BeforeEach
    void setUp() {
        dbManager = new DatabaseManager(tempDir.resolve("test.db"));
        historyRepo = new TaskHistoryRepository(dbManager);
        engine = new TaskEngine(4, historyRepo);
    }

    @AfterEach
    void tearDown() {
        engine.shutdown();
        dbManager.close();
    }

    @Test
    void testSuccessfulExecutionAndStateTransitions() throws Exception {
        CountDownLatch finishLatch = new CountDownLatch(1);
        AtomicBoolean ran = new AtomicBoolean(false);

        TaskPlugin mockPlugin = new TaskPlugin() {
            @Override
            public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
                ran.set(true);
                context.updateProgress(1, 1);
                context.updateMessage("Done");
                return TaskExecutionResult.success(1, 100, "Done successfully", Collections.emptyMap(), 50);
            }

            @Override
            public List<TaskParameterDescriptor> getParameterDescriptors() {
                return Collections.emptyList();
            }
        };

        TaskDefinition def = new TaskDefinition("test-task", "Test Task", "1.0", "Author", "Desc", "Test", "1.0", mockPlugin, null);

        engine.addListener(new TaskEngineListener() {
            @Override
            public void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
                if (newState == TaskState.COMPLETED) {
                    finishLatch.countDown();
                }
            }

            @Override
            public void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message) {}

            @Override
            public void onTaskLogAppended(String taskId, String executionId, String logLine) {}
        });

        String execId = engine.runTask(def, null);
        assertNotNull(execId);

        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "Task did not complete within timeout");
        assertTrue(ran.get());

        // Verify history repo record
        var records = historyRepo.findAllRecent(10);
        assertFalse(records.isEmpty());
        assertEquals("test-task", records.get(0).taskId());
        assertEquals(TaskState.COMPLETED, records.get(0).state());
    }

    @Test
    void testCooperativeCancellation() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch cancelLatch = new CountDownLatch(1);

        TaskPlugin cancellablePlugin = new TaskPlugin() {
            @Override
            public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
                startLatch.countDown();
                while (true) {
                    context.checkPauseAndCancel();
                    Thread.sleep(20);
                }
            }

            @Override
            public List<TaskParameterDescriptor> getParameterDescriptors() {
                return Collections.emptyList();
            }
        };

        TaskDefinition def = new TaskDefinition("cancel-task", "Cancel Task", "1.0", "Author", "Desc", "Test", "1.0", cancellablePlugin, null);

        engine.addListener(new TaskEngineListener() {
            @Override
            public void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
                if (newState == TaskState.CANCELLED) {
                    cancelLatch.countDown();
                }
            }

            @Override
            public void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message) {}

            @Override
            public void onTaskLogAppended(String taskId, String executionId, String logLine) {}
        });

        engine.runTask(def, null);
        assertTrue(startLatch.await(3, TimeUnit.SECONDS));

        boolean cancelled = engine.cancelTask("cancel-task");
        assertTrue(cancelled);

        assertTrue(cancelLatch.await(5, TimeUnit.SECONDS), "Task was not cancelled cooperatively in time");
    }

    @Test
    void testCooperativePauseAndResume() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch pausedLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        TaskPlugin pausablePlugin = new TaskPlugin() {
            @Override
            public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
                startedLatch.countDown();
                for (int i = 0; i < 5; i++) {
                    context.checkPauseAndCancel();
                    Thread.sleep(50);
                }
                return TaskExecutionResult.success(5, 50, "Pausable completed", Collections.emptyMap(), 100);
            }

            @Override
            public List<TaskParameterDescriptor> getParameterDescriptors() {
                return Collections.emptyList();
            }
        };

        TaskDefinition def = new TaskDefinition("pause-task", "Pause Task", "1.0", "Author", "Desc", "Test", "1.0", pausablePlugin, null);

        engine.addListener(new TaskEngineListener() {
            @Override
            public void onTaskStateChanged(String taskId, String executionId, TaskState oldState, TaskState newState, String message) {
                if (newState == TaskState.PAUSED) {
                    pausedLatch.countDown();
                } else if (newState == TaskState.COMPLETED) {
                    finishLatch.countDown();
                }
            }

            @Override
            public void onTaskProgressUpdated(String taskId, String executionId, double workDone, double totalWork, String message) {}

            @Override
            public void onTaskLogAppended(String taskId, String executionId, String logLine) {}
        });

        engine.runTask(def, null);
        assertTrue(startedLatch.await(3, TimeUnit.SECONDS));

        // Pause
        assertTrue(engine.pauseTask("pause-task"));
        assertTrue(pausedLatch.await(3, TimeUnit.SECONDS));
        assertEquals(TaskState.PAUSED, engine.getActiveTaskHandle("pause-task").getState());

        // Resume
        assertTrue(engine.resumeTask("pause-task"));
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    void testOverlapPolicySkipsExecutionIfAlreadyRunning() throws Exception {
        CountDownLatch blockLatch = new CountDownLatch(1);

        TaskPlugin blockingPlugin = new TaskPlugin() {
            @Override
            public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
                blockLatch.await(4, TimeUnit.SECONDS);
                return TaskExecutionResult.success(1, 1, "Done", Collections.emptyMap(), 10);
            }

            @Override
            public List<TaskParameterDescriptor> getParameterDescriptors() {
                return Collections.emptyList();
            }
        };

        TaskDefinition def = new TaskDefinition("overlap-task", "Overlap Task", "1.0", "Author", "Desc", "Test", "1.0", blockingPlugin, null);

        String firstExec = engine.runTask(def, null);
        assertNotNull(firstExec);

        // Attempting to run again while RUNNING should be skipped per Overlap Policy
        String secondExec = engine.runTask(def, null);
        assertNull(secondExec, "Overlap policy must return null and skip execution if already running");

        blockLatch.countDown();
    }
}

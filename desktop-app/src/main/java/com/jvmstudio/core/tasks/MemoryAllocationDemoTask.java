package com.jvmstudio.core.tasks;

import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Educational Memory Allocation & Garbage Collection demonstration task.
 * Demonstrates bounded heap allocation, reference retention, reference release,
 * and observational monitoring of JVM garbage collection behavior.
 */
@TaskInfo(
        id = "memory-allocation-demo",
        name = "JVM Heap Allocation & GC Demo",
        version = "1.0.0",
        author = "JVM Studio Core",
        description = "Allocates bounded byte arrays in the JVM heap, observes generational memory usage, and demonstrates GC dynamics.",
        apiVersion = "1.0",
        category = "Educational Lab"
)
public class MemoryAllocationDemoTask implements TaskPlugin {

    // Maximum safe ceiling to guard against JVM heap exhaustion
    private static final long MAX_ALLOWED_TOTAL_ALLOCATION_BYTES = 512L * 1024 * 1024; // 512 MB

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
                TaskParameterDescriptor.of(
                        "chunkCount",
                        "Number of Chunks",
                        TaskParameterType.INTEGER,
                        6,
                        "Number of separate memory buffers to allocate (max 20)."
                ),
                TaskParameterDescriptor.of(
                        "chunkSizeMb",
                        "Chunk Size (MB)",
                        TaskParameterType.INTEGER,
                        25,
                        "Size of each chunk in megabytes (max 100 MB)."
                ),
                TaskParameterDescriptor.of(
                        "holdSeconds",
                        "Retention Hold Duration (s)",
                        TaskParameterType.INTEGER,
                        8,
                        "Time to keep references active in memory before releasing."
                ),
                TaskParameterDescriptor.of(
                        "callGcOnRelease",
                        "Request GC After Release",
                        TaskParameterType.BOOLEAN,
                        true,
                        "Call System.gc() after clearing references (educational demonstration)."
                )
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        long startTime = System.currentTimeMillis();
        context.log("Initiating JVM Memory Allocation & Garbage Collection Demonstration...");

        int chunkCount = getIntParam(parameters, "chunkCount", 6);
        int chunkSizeMb = getIntParam(parameters, "chunkSizeMb", 25);
        int holdSeconds = getIntParam(parameters, "holdSeconds", 8);
        boolean callGc = getBoolParam(parameters, "callGcOnRelease", true);

        // Bound parameters for safety
        chunkCount = Math.max(1, Math.min(20, chunkCount));
        chunkSizeMb = Math.max(1, Math.min(100, chunkSizeMb));
        holdSeconds = Math.max(1, Math.min(60, holdSeconds));

        long totalRequestedBytes = (long) chunkCount * chunkSizeMb * 1024L * 1024L;

        // Verify against JVM Heap limits
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsageBefore = memBean.getHeapMemoryUsage();
        long maxHeap = heapUsageBefore.getMax();
        long usedHeap = heapUsageBefore.getUsed();
        long availableApprox = maxHeap > 0 ? (maxHeap - usedHeap) : (Runtime.getRuntime().freeMemory());

        context.log(String.format(Locale.ROOT,
                "Heap State Before: Used=%.1f MB, Committed=%.1f MB, Max=%.1f MB",
                heapUsageBefore.getUsed() / (1024.0 * 1024.0),
                heapUsageBefore.getCommitted() / (1024.0 * 1024.0),
                heapUsageBefore.getMax() / (1024.0 * 1024.0)));

        if (totalRequestedBytes > MAX_ALLOWED_TOTAL_ALLOCATION_BYTES) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Requested total allocation of %.1f MB exceeds the safety threshold of %.1f MB.",
                    totalRequestedBytes / (1024.0 * 1024.0), MAX_ALLOWED_TOTAL_ALLOCATION_BYTES / (1024.0 * 1024.0)));
        }

        if (maxHeap > 0 && totalRequestedBytes > (availableApprox * 0.75)) {
            throw new IllegalArgumentException(String.format(Locale.ROOT,
                    "Allocation of %.1f MB would consume >75%% of remaining heap (%.1f MB available). Aborting to protect JVM.",
                    totalRequestedBytes / (1024.0 * 1024.0), availableApprox / (1024.0 * 1024.0)));
        }

        context.log(String.format(Locale.ROOT,
                "Safe allocation parameters verified. Allocating %d chunk(s) x %d MB = %.1f MB total.",
                chunkCount, chunkSizeMb, totalRequestedBytes / (1024.0 * 1024.0)));

        List<byte[]> memoryHolders = new ArrayList<>();
        int bytesPerChunk = chunkSizeMb * 1024 * 1024;

        try {
            for (int i = 0; i < chunkCount; i++) {
                context.checkPauseAndCancel();

                context.updateMessage(String.format(Locale.ROOT, "Allocating chunk %d/%d (%d MB)...", i + 1, chunkCount, chunkSizeMb));

                // Allocate array and write dummy bytes to touch memory pages
                byte[] chunk = new byte[bytesPerChunk];
                Arrays.fill(chunk, 0, Math.min(4096, bytesPerChunk), (byte) (i + 1));
                memoryHolders.add(chunk);

                MemoryUsage currentHeap = memBean.getHeapMemoryUsage();
                context.log(String.format(Locale.ROOT,
                        "Chunk %d allocated: +%d MB (Current Heap Used: %.1f MB / %.1f MB)",
                        i + 1, chunkSizeMb, currentHeap.getUsed() / (1024.0 * 1024.0), currentHeap.getCommitted() / (1024.0 * 1024.0)));

                context.updateProgress(i + 1, chunkCount + holdSeconds + 1);
                Thread.sleep(150);
            }

            MemoryUsage peakHeap = memBean.getHeapMemoryUsage();
            context.log(String.format(Locale.ROOT,
                    "All %d chunks active in heap. Peak Heap Used: %.1f MB. Holding references for %d seconds...",
                    chunkCount, peakHeap.getUsed() / (1024.0 * 1024.0), holdSeconds));

            // Hold phase: keep references reachable from GC root (this list)
            for (int s = 0; s < holdSeconds; s++) {
                context.checkPauseAndCancel();
                int remaining = holdSeconds - s;
                context.updateMessage(String.format("Holding references in memory... %d s remaining", remaining));
                context.updateProgress(chunkCount + s + 1, chunkCount + holdSeconds + 1);
                Thread.sleep(1000);
            }

            // Release phase: dereference objects so they become eligible for Garbage Collection
            context.updateMessage("Releasing object references...");
            context.log("Clearing reference list. The allocated byte arrays are now unreachable and eligible for GC.");
            memoryHolders.clear();
            memoryHolders = null; // nullify local reference

            if (callGc) {
                context.log("Invoking System.gc(). [Educational Note]: System.gc() is a hint to the JVM. The runtime decides the exact time and generation to sweep.");
                System.gc();
                Thread.sleep(300); // brief sleep to let concurrent collectors record statistics
            }

            MemoryUsage afterGcHeap = memBean.getHeapMemoryUsage();
            context.log(String.format(Locale.ROOT,
                    "Post-release Heap State: Used=%.1f MB, Committed=%.1f MB.",
                    afterGcHeap.getUsed() / (1024.0 * 1024.0), afterGcHeap.getCommitted() / (1024.0 * 1024.0)));

            context.updateProgress(chunkCount + holdSeconds + 1, chunkCount + holdSeconds + 1);

            long durationMs = System.currentTimeMillis() - startTime;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("chunksAllocated", chunkCount);
            details.put("chunkSizeMb", chunkSizeMb);
            details.put("totalAllocatedBytes", totalRequestedBytes);
            details.put("holdSeconds", holdSeconds);
            details.put("heapUsedBeforeMb", heapUsageBefore.getUsed() / (1024.0 * 1024.0));
            details.put("heapUsedPeakMb", peakHeap.getUsed() / (1024.0 * 1024.0));
            details.put("heapUsedAfterMb", afterGcHeap.getUsed() / (1024.0 * 1024.0));

            String summary = String.format(Locale.ROOT,
                    "Demo complete: Allocated %.1f MB across %d chunks, held for %ds, references cleared. Final heap: %.1f MB.",
                    totalRequestedBytes / (1024.0 * 1024.0), chunkCount, holdSeconds, afterGcHeap.getUsed() / (1024.0 * 1024.0));

            return TaskExecutionResult.success(chunkCount, totalRequestedBytes, summary, details, durationMs);
        } finally {
            if (memoryHolders != null) {
                memoryHolders.clear();
            }
        }
    }

    private static int getIntParam(Map<String, Object> params, String key, int def) {
        Object val = params.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }
        return def;
    }

    private static boolean getBoolParam(Map<String, Object> params, String key, boolean def) {
        Object val = params.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s.trim());
        return def;
    }
}

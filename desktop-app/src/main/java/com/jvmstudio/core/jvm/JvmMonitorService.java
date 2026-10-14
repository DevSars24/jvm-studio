package com.jvmstudio.core.jvm;

import com.jvmstudio.core.model.JvmMetricsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Real-time JVM monitoring service polling standard JMX Management Beans.
 * Gathers heap, non-heap, thread, GC, and platform metrics with bounded history.
 */
public class JvmMonitorService {
    private static final Logger logger = LoggerFactory.getLogger(JvmMonitorService.class);
    private static final int DEFAULT_HISTORY_SIZE = 60;

    private final MemoryMXBean memoryMXBean;
    private final ThreadMXBean threadMXBean;
    private final RuntimeMXBean runtimeMXBean;
    private final ClassLoadingMXBean classLoadingMXBean;
    private final OperatingSystemMXBean osMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;

    private final int historyCapacity;
    private final Deque<JvmMetricsSnapshot> history = new ArrayDeque<>();
    private final List<MetricsListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService sampler;
    private long lastTotalGcCount = 0;
    private long lastTotalGcTimeMs = 0;

    public interface MetricsListener {
        void onMetricsSampled(JvmMetricsSnapshot snapshot);
    }

    public record GcCollectorInfo(String name, long collectionCount, long collectionTimeMs) {}

    public JvmMonitorService(int samplingIntervalMs) {
        this(samplingIntervalMs, DEFAULT_HISTORY_SIZE);
    }

    public JvmMonitorService(int samplingIntervalMs, int historyCapacity) {
        this.historyCapacity = Math.max(10, historyCapacity);
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.threadMXBean = ManagementFactory.getThreadMXBean();
        this.runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        this.classLoadingMXBean = ManagementFactory.getClassLoadingMXBean();
        this.osMXBean = ManagementFactory.getOperatingSystemMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        // Initialize GC baseline
        for (GarbageCollectorMXBean gc : gcBeans) {
            long count = gc.getCollectionCount();
            long time = gc.getCollectionTime();
            if (count > 0) lastTotalGcCount += count;
            if (time > 0) lastTotalGcTimeMs += time;
        }

        this.sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jvmstudio-metrics-sampler");
            t.setDaemon(true);
            return t;
        });

        long interval = Math.max(250, samplingIntervalMs);
        this.sampler.scheduleAtFixedRate(this::sampleMetrics, 0, interval, TimeUnit.MILLISECONDS);
        logger.info("JvmMonitorService initialized with interval {}ms", interval);
    }

    public void addListener(MetricsListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(MetricsListener listener) {
        listeners.remove(listener);
    }

    public synchronized List<JvmMetricsSnapshot> getHistory() {
        return new ArrayList<>(history);
    }

    public synchronized JvmMetricsSnapshot getLatestSnapshot() {
        return history.peekLast();
    }

    private void sampleMetrics() {
        try {
            Instant now = Instant.now();

            MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
            MemoryUsage nonHeap = memoryMXBean.getNonHeapMemoryUsage();

            long heapUsed = heap.getUsed();
            long heapCommitted = heap.getCommitted();
            long heapMax = heap.getMax();

            long nonHeapUsed = nonHeap.getUsed();
            long nonHeapCommitted = nonHeap.getCommitted();

            int liveThreads = threadMXBean.getThreadCount();
            int peakThreads = threadMXBean.getPeakThreadCount();
            int daemonThreads = threadMXBean.getDaemonThreadCount();

            long currentTotalGcCount = 0;
            long currentTotalGcTime = 0;
            for (GarbageCollectorMXBean gc : gcBeans) {
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c > 0) currentTotalGcCount += c;
                if (t > 0) currentTotalGcTime += t;
            }

            long gcCountDelta = Math.max(0, currentTotalGcCount - lastTotalGcCount);
            long gcTimeDelta = Math.max(0, currentTotalGcTime - lastTotalGcTimeMs);
            lastTotalGcCount = currentTotalGcCount;
            lastTotalGcTimeMs = currentTotalGcTime;

            long loadedClasses = classLoadingMXBean.getLoadedClassCount();
            int cpus = osMXBean.getAvailableProcessors();
            double systemLoad = osMXBean.getSystemLoadAverage();

            JvmMetricsSnapshot snapshot = new JvmMetricsSnapshot(
                    now,
                    heapUsed,
                    heapCommitted,
                    heapMax,
                    nonHeapUsed,
                    nonHeapCommitted,
                    liveThreads,
                    peakThreads,
                    daemonThreads,
                    currentTotalGcCount,
                    currentTotalGcTime,
                    gcCountDelta,
                    gcTimeDelta,
                    loadedClasses,
                    cpus,
                    systemLoad
            );

            synchronized (this) {
                if (history.size() >= historyCapacity) {
                    history.pollFirst();
                }
                history.addLast(snapshot);
            }

            for (MetricsListener listener : listeners) {
                try {
                    listener.onMetricsSampled(snapshot);
                } catch (Exception ex) {
                    logger.warn("Metrics listener error", ex);
                }
            }
        } catch (Throwable t) {
            logger.warn("Error sampling JVM metrics: {}", t.getMessage());
        }
    }

    public List<GcCollectorInfo> getGcCollectorDetails() {
        List<GcCollectorInfo> list = new ArrayList<>();
        for (GarbageCollectorMXBean gc : gcBeans) {
            list.add(new GcCollectorInfo(gc.getName(), gc.getCollectionCount(), gc.getCollectionTime()));
        }
        return list;
    }

    public String getJavaVersion() {
        return System.getProperty("java.version", "Unknown");
    }

    public String getJvmName() {
        return runtimeMXBean.getVmName() + " (" + runtimeMXBean.getVmVendor() + ")";
    }

    public String getOsInfo() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")";
    }

    public long getUptimeMs() {
        return runtimeMXBean.getUptime();
    }

    public void stop() {
        sampler.shutdownNow();
        logger.info("JvmMonitorService stopped.");
    }
}

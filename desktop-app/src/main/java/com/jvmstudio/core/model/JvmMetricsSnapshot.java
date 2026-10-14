package com.jvmstudio.core.model;

import java.time.Instant;

/**
 * Point-in-time metrics sample captured from standard JVM MXBeans.
 */
public record JvmMetricsSnapshot(
        Instant timestamp,
        long heapUsedBytes,
        long heapCommittedBytes,
        long heapMaxBytes,
        long nonHeapUsedBytes,
        long nonHeapCommittedBytes,
        int liveThreadCount,
        int peakThreadCount,
        int daemonThreadCount,
        long totalGcCount,
        long totalGcTimeMs,
        long gcCountDelta,
        long gcTimeMsDelta,
        long loadedClassCount,
        int availableProcessors,
        double systemLoadAverage
) {
    public double getHeapUsageRatio() {
        if (heapMaxBytes > 0) {
            return (double) heapUsedBytes / heapMaxBytes;
        } else if (heapCommittedBytes > 0) {
            return (double) heapUsedBytes / heapCommittedBytes;
        }
        return 0.0;
    }

    public double getHeapUsedMb() {
        return heapUsedBytes / (1024.0 * 1024.0);
    }

    public double getHeapCommittedMb() {
        return heapCommittedBytes / (1024.0 * 1024.0);
    }

    public double getHeapMaxMb() {
        return heapMaxBytes > 0 ? (heapMaxBytes / (1024.0 * 1024.0)) : -1.0;
    }

    public double getNonHeapUsedMb() {
        return nonHeapUsedBytes / (1024.0 * 1024.0);
    }
}

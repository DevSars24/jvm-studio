package com.jvmstudio.core.tasks;

import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Built-in Folder Analyzer task providing file count, recursive byte size,
 * top largest files, and file extension distribution with safe symlink handling.
 */
@TaskInfo(
        id = "folder-analyzer",
        name = "Disk & Folder Storage Analyzer",
        version = "1.0.0",
        author = "JVM Studio Core",
        description = "Scans directories, computes storage volume, extracts top largest files, and charts file-type distribution.",
        apiVersion = "1.0",
        category = "Analysis"
)
public class FolderAnalyzerTask implements TaskPlugin {

    public record FileSizeRecord(String relativePath, long bytes, double mb) {}

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
                TaskParameterDescriptor.ofRequired(
                        "folder",
                        "Target Folder",
                        TaskParameterType.DIRECTORY,
                        "",
                        "Directory path to scan and analyze."
                ),
                TaskParameterDescriptor.of(
                        "maxLargestFiles",
                        "Top Largest Files",
                        TaskParameterType.INTEGER,
                        10,
                        "Number of largest files to highlight."
                )
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        long startTime = System.currentTimeMillis();
        context.log("Initiating Folder Analyzer...");

        String folderStr = (String) parameters.get("folder");
        if (folderStr == null || folderStr.isBlank()) {
            throw new IllegalArgumentException("Target folder path must be specified.");
        }

        Path targetDir = Path.of(folderStr).toAbsolutePath().normalize();
        if (!Files.exists(targetDir) || !Files.isDirectory(targetDir)) {
            throw new IllegalArgumentException("Target directory does not exist: " + targetDir);
        }

        int maxLargest = 10;
        Object maxVal = parameters.get("maxLargestFiles");
        if (maxVal instanceof Number n) maxLargest = n.intValue();
        else if (maxVal instanceof String s && !s.isBlank()) {
            try { maxLargest = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }

        context.updateIndeterminate();
        context.updateMessage("Traversing directory tree...");
        context.log("Scanning folder: " + targetDir);

        List<Path> discoveredFiles = new ArrayList<>();
        long totalDirectories = 0;

        // Pass 1: Discover files safely without following circular symlinks
        EnumSet<FileVisitOption> options = EnumSet.noneOf(FileVisitOption.class);
        final int[] dirCount = new int[]{0};

        Files.walkFileTree(targetDir, options, Integer.MAX_VALUE, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                dirCount[0]++;
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    discoveredFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                context.logWarn("Skipping inaccessible file/dir (" + exc.getClass().getSimpleName() + "): " + file);
                return FileVisitResult.CONTINUE;
            }
        });

        int totalFiles = discoveredFiles.size();
        totalDirectories = dirCount[0];
        context.log(String.format(Locale.ROOT, "Discovered %d file(s) across %d directorie(s).", totalFiles, totalDirectories));

        if (totalFiles == 0) {
            return TaskExecutionResult.success(0, 0, "Folder is empty or contains no regular files.", Collections.emptyMap(), System.currentTimeMillis() - startTime);
        }

        long totalBytes = 0;
        Map<String, Long> extensionCounts = new HashMap<>();
        Map<String, Long> extensionBytes = new HashMap<>();
        List<FileSizeRecord> fileSizes = new ArrayList<>(totalFiles);

        for (int i = 0; i < totalFiles; i++) {
            if (i % 250 == 0) {
                context.checkPauseAndCancel();
                context.updateProgress(i, totalFiles);
                context.updateMessage(String.format("Analyzing files (%d/%d)...", i, totalFiles));
            }

            Path f = discoveredFiles.get(i);
            try {
                long size = Files.size(f);
                totalBytes += size;

                String rel = targetDir.relativize(f).toString();
                fileSizes.add(new FileSizeRecord(rel, size, size / (1024.0 * 1024.0)));

                String ext = getFileExtension(f.getFileName().toString());
                extensionCounts.merge(ext, 1L, Long::sum);
                extensionBytes.merge(ext, size, Long::sum);
            } catch (IOException ex) {
                context.logWarn("Failed to stat " + f + ": " + ex.getMessage());
            }
        }

        context.updateProgress(totalFiles, totalFiles);
        context.updateMessage("Computing metrics and top files...");

        // Sort largest files
        List<FileSizeRecord> largestFiles = fileSizes.stream()
                .sorted(Comparator.comparingLong(FileSizeRecord::bytes).reversed())
                .limit(maxLargest)
                .toList();

        // Sort extensions by count
        List<Map.Entry<String, Long>> topExtensions = extensionCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(12)
                .toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalFiles", totalFiles);
        details.put("totalDirectories", totalDirectories);
        details.put("totalBytes", totalBytes);
        details.put("totalMb", totalBytes / (1024.0 * 1024.0));

        List<String> largestDesc = largestFiles.stream()
                .map(r -> String.format(Locale.ROOT, "%s (%.2f MB)", r.relativePath(), r.mb()))
                .toList();
        details.put("largestFiles", largestDesc);

        Map<String, String> extStats = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : topExtensions) {
            long bytes = extensionBytes.getOrDefault(e.getKey(), 0L);
            extStats.put(e.getKey(), String.format(Locale.ROOT, "%d file(s) (%.2f MB)", e.getValue(), bytes / (1024.0 * 1024.0)));
        }
        details.put("extensionDistribution", extStats);

        long durationMs = System.currentTimeMillis() - startTime;
        String summary = String.format(Locale.ROOT,
                "Analyzed %d file(s) across %d directorie(s). Total volume: %.2f MB. Largest: %s",
                totalFiles, totalDirectories, totalBytes / (1024.0 * 1024.0),
                largestFiles.isEmpty() ? "None" : largestFiles.get(0).relativePath() + " (" + String.format(Locale.ROOT, "%.2f MB", largestFiles.get(0).mb()) + ")");

        context.log("Analysis finished in " + durationMs + " ms. " + summary);
        return TaskExecutionResult.success(totalFiles, totalBytes, summary, details, durationMs);
    }

    private static String getFileExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot > 0 && dot < filename.length() - 1) {
            return filename.substring(dot).toLowerCase(Locale.ROOT);
        }
        return "[no extension]";
    }
}

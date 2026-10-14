package com.jvmstudio.core.tasks;

import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Built-in SHA-256 File Verifier task. Computes cryptographic checksums using
 * standard Java Security MessageDigest with real chunked streaming and cooperative cancellation.
 */
@TaskInfo(
        id = "sha256-verifier",
        name = "SHA-256 Cryptographic Verifier",
        version = "1.0.0",
        author = "JVM Studio Core",
        description = "Computes cryptographic SHA-256 hashes for files with chunked I/O and interactive clipboard export.",
        apiVersion = "1.0",
        category = "Security"
)
public class Sha256VerifierTask implements TaskPlugin {

    public record HashResultEntry(String fileName, String relativePath, long bytes, String sha256, long durationMs) {}

    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
                TaskParameterDescriptor.ofRequired(
                        "target",
                        "Target File or Folder",
                        TaskParameterType.FILE,
                        "",
                        "Path to a single file or directory to hash."
                ),
                TaskParameterDescriptor.of(
                        "maxFiles",
                        "Maximum Files to Process",
                        TaskParameterType.INTEGER,
                        50,
                        "Safety limit on number of files to hash in one run."
                )
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        long startTime = System.currentTimeMillis();
        context.log("Initiating SHA-256 Hash Verification...");

        String targetStr = (String) parameters.get("target");
        if (targetStr == null || targetStr.isBlank()) {
            throw new IllegalArgumentException("Target file or folder path must be specified.");
        }

        Path target = Path.of(targetStr).toAbsolutePath().normalize();
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Target path does not exist: " + target);
        }

        int maxFiles = 50;
        Object maxVal = parameters.get("maxFiles");
        if (maxVal instanceof Number n) maxFiles = n.intValue();
        else if (maxVal instanceof String s && !s.isBlank()) {
            try { maxFiles = Integer.parseInt(s.trim()); } catch (Exception ignored) {}
        }

        List<Path> filesToHash = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            filesToHash.add(target);
        } else {
            context.updateIndeterminate();
            context.updateMessage("Discovering candidate files...");
            try (Stream<Path> s = Files.walk(target)) {
                filesToHash = s.filter(Files::isRegularFile)
                        .limit(maxFiles)
                        .collect(Collectors.toList());
            }
        }

        int totalFiles = filesToHash.size();
        context.log("Target contains " + totalFiles + " file(s) to verify.");

        if (totalFiles == 0) {
            return TaskExecutionResult.success(0, 0, "No files found to hash.", Collections.emptyMap(), System.currentTimeMillis() - startTime);
        }

        long totalBytes = 0;
        for (Path f : filesToHash) {
            try { totalBytes += Files.size(f); } catch (Exception ignored) {}
        }

        List<HashResultEntry> entries = new ArrayList<>();
        long bytesProcessed = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        HexFormat hexFormat = HexFormat.of();

        for (int i = 0; i < totalFiles; i++) {
            context.checkPauseAndCancel();

            Path file = filesToHash.get(i);
            String rel = target.equals(file) ? file.getFileName().toString() : target.relativize(file).toString();
            context.updateMessage(String.format("Hashing (%d/%d): %s", i + 1, totalFiles, file.getFileName()));

            long fileStart = System.currentTimeMillis();
            long fileSize = Files.size(file);

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file, StandardOpenOption.READ)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    context.checkPauseAndCancel();
                    md.update(buffer, 0, read);
                    bytesProcessed += read;
                    context.updateProgress(bytesProcessed, totalBytes);
                }
            }

            String sha256 = hexFormat.formatHex(md.digest());
            long fileDuration = System.currentTimeMillis() - fileStart;

            HashResultEntry entry = new HashResultEntry(file.getFileName().toString(), rel, fileSize, sha256, fileDuration);
            entries.add(entry);
            context.log(String.format(Locale.ROOT, "Hashed: %s -> %s (%d ms)", rel, sha256, fileDuration));
        }

        context.updateProgress(totalBytes, totalBytes);
        long durationMs = System.currentTimeMillis() - startTime;

        // Store structured entries for UI table access
        context.putResultData("hashEntries", entries);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalFiles", totalFiles);
        details.put("totalBytes", totalBytes);

        List<String> displayList = new ArrayList<>();
        for (HashResultEntry e : entries) {
            displayList.add(e.fileName() + " = " + e.sha256());
        }
        details.put("hashes", displayList);

        String summary = String.format(Locale.ROOT,
                "Computed SHA-256 for %d file(s) (%.2f MB total) in %d ms.",
                totalFiles, totalBytes / (1024.0 * 1024.0), durationMs);

        return TaskExecutionResult.success(totalFiles, totalBytes, summary, details, durationMs);
    }
}

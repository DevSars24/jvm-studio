package com.jvmstudio.core.tasks;

import com.jvmstudio.core.model.CollisionPolicy;
import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in File Backup task implementing recursive copy, loop detection,
 * configurable collision policies, and chunked byte-level progress reporting.
 */
@TaskInfo(
        id = "file-backup",
        name = "File & Folder Backup Engine",
        version = "1.0.0",
        author = "JVM Studio Core",
        description = "Performs recursive backup with cycle prevention, chunked byte progress, and configurable collision handling.",
        apiVersion = "1.0",
        category = "File Operations"
)
public class FileBackupTask implements TaskPlugin {

    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB chunk buffer

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
                TaskParameterDescriptor.ofRequired(
                        "source",
                        "Source Folder",
                        TaskParameterType.DIRECTORY,
                        "",
                        "Directory containing files to backup."
                ),
                TaskParameterDescriptor.ofRequired(
                        "destination",
                        "Destination Folder",
                        TaskParameterType.DIRECTORY,
                        "",
                        "Target directory where backup will be stored."
                ),
                TaskParameterDescriptor.ofChoice(
                        "collisionPolicy",
                        "Collision Policy",
                        CollisionPolicy.RENAME_TIMESTAMP.name(),
                        "Action to take if destination file already exists.",
                        List.of(CollisionPolicy.OVERWRITE.name(), CollisionPolicy.SKIP.name(), CollisionPolicy.RENAME_TIMESTAMP.name())
                )
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        long startTime = System.currentTimeMillis();
        context.log("Initiating File Backup Task...");

        String srcStr = (String) parameters.get("source");
        String dstStr = (String) parameters.get("destination");
        String policyStr = (String) parameters.getOrDefault("collisionPolicy", CollisionPolicy.RENAME_TIMESTAMP.name());

        if (srcStr == null || srcStr.isBlank() || dstStr == null || dstStr.isBlank()) {
            throw new IllegalArgumentException("Both source and destination directories must be specified.");
        }

        Path source = Path.of(srcStr).toAbsolutePath().normalize();
        Path destination = Path.of(dstStr).toAbsolutePath().normalize();

        if (!Files.exists(source) || !Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source directory does not exist: " + source);
        }

        if (source.equals(destination)) {
            throw new IllegalArgumentException("Source and destination cannot be the exact same folder.");
        }

        CollisionPolicy collisionPolicy = CollisionPolicy.valueOf(policyStr);
        context.log("Source: " + source);
        context.log("Destination: " + destination);
        context.log("Collision Policy: " + collisionPolicy);

        // Pre-scan to discover files and calculate total bytes, excluding the destination if nested
        context.updateIndeterminate();
        context.updateMessage("Indexing files and calculating byte total...");

        List<Path> filesToCopy = new ArrayList<>();
        long totalBytes = 0;

        Files.createDirectories(destination);

        boolean destinationIsInsideSource = destination.startsWith(source);
        if (destinationIsInsideSource) {
            context.logWarn("Destination is located inside source directory; excluding destination from scan to prevent recursive backup loop.");
        }

        try {
            Files.walkFileTree(source, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (destinationIsInsideSource && dir.toAbsolutePath().normalize().startsWith(destination)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        filesToCopy.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    context.logWarn("Permission denied or error reading " + file + ": " + exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception ex) {
            throw new IOException("Failed during source indexing: " + ex.getMessage(), ex);
        }

        for (Path f : filesToCopy) {
            try {
                totalBytes += Files.size(f);
            } catch (IOException ignored) {}
        }

        int totalFiles = filesToCopy.size();
        context.log(String.format(Locale.ROOT, "Discovered %d file(s), total %s bytes (%.2f MB).",
                totalFiles, totalBytes, totalBytes / (1024.0 * 1024.0)));

        if (totalFiles == 0) {
            return TaskExecutionResult.success(0, 0, "No files found to backup in source folder.", Collections.emptyMap(), System.currentTimeMillis() - startTime);
        }

        long bytesCopied = 0;
        int filesCopied = 0;
        int filesSkipped = 0;
        int filesRenamed = 0;
        List<String> failedFiles = new ArrayList<>();

        byte[] buffer = new byte[BUFFER_SIZE];

        for (int i = 0; i < totalFiles; i++) {
            context.checkPauseAndCancel();

            Path srcFile = filesToCopy.get(i);
            Path relativePath = source.relativize(srcFile);
            Path targetFile = destination.resolve(relativePath);

            context.updateMessage(String.format("Copying (%d/%d): %s", i + 1, totalFiles, relativePath));

            // Collision handling
            if (Files.exists(targetFile)) {
                if (collisionPolicy == CollisionPolicy.SKIP) {
                    context.log("Skipping existing file: " + relativePath);
                    filesSkipped++;
                    continue;
                } else if (collisionPolicy == CollisionPolicy.RENAME_TIMESTAMP) {
                    targetFile = generateTimestampedPath(targetFile);
                    filesRenamed++;
                    context.log("Renamed collision to: " + targetFile.getFileName());
                }
            }

            // Ensure parent directory exists
            if (targetFile.getParent() != null) {
                Files.createDirectories(targetFile.getParent());
            }

            try (InputStream in = Files.newInputStream(srcFile, StandardOpenOption.READ);
                 OutputStream out = Files.newOutputStream(targetFile,
                         StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {

                int read;
                while ((read = in.read(buffer)) != -1) {
                    context.checkPauseAndCancel();
                    out.write(buffer, 0, read);
                    bytesCopied += read;
                    context.updateProgress(bytesCopied, totalBytes);
                }
                filesCopied++;
            } catch (InterruptedException ie) {
                throw ie;
            } catch (Exception ex) {
                context.logError("Failed to copy " + relativePath + ": " + ex.getMessage(), ex);
                failedFiles.add(relativePath.toString() + " (" + ex.getMessage() + ")");
            }
        }

        context.updateProgress(totalBytes, totalBytes);
        long durationMs = System.currentTimeMillis() - startTime;

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("filesCopied", filesCopied);
        details.put("filesSkipped", filesSkipped);
        details.put("filesRenamed", filesRenamed);
        details.put("bytesCopied", bytesCopied);
        details.put("failedCount", failedFiles.size());
        details.put("failures", failedFiles);

        String summary = String.format(Locale.ROOT,
                "Backup completed: %d copied, %d skipped, %d renamed, %d failed. Total size: %.2f MB in %d ms.",
                filesCopied, filesSkipped, filesRenamed, failedFiles.size(),
                bytesCopied / (1024.0 * 1024.0), durationMs);

        context.log(summary);
        return TaskExecutionResult.success(filesCopied, bytesCopied, summary, details, durationMs);
    }

    private static Path generateTimestampedPath(Path original) {
        String fileName = original.getFileName().toString();
        String baseName = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            baseName = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.ROOT).format(new Date());
        String newName = baseName + "_backup_" + timestamp + extension;
        return original.resolveSibling(newName);
    }
}

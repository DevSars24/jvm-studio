package com.jvmstudio.plugin.sample;

import com.jvmstudio.plugin.api.TaskContext;
import com.jvmstudio.plugin.api.TaskExecutionResult;
import com.jvmstudio.plugin.api.TaskInfo;
import com.jvmstudio.plugin.api.TaskParameterDescriptor;
import com.jvmstudio.plugin.api.TaskParameterType;
import com.jvmstudio.plugin.api.TaskPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Sample Plugin demonstrating the JVM Studio Plugin Architecture.
 * Analyzes text and source files to extract word frequencies, vocabulary metrics,
 * and top keywords while honoring pause and cancellation checkpoints.
 */
@TaskInfo(
        id = "word-frequency-counter",
        name = "Text & Code Word Counter",
        version = "1.0.0",
        author = "JVM Studio Core Team",
        description = "Recursively parses text and source files, calculates word frequencies, counts total lines, and surfaces top keywords.",
        apiVersion = "1.0",
        category = "Text Analysis"
)
public class WordFrequencyCounterPlugin implements TaskPlugin {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
            "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
            "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us"
    );

    @Override
    public List<TaskParameterDescriptor> getParameterDescriptors() {
        return List.of(
                TaskParameterDescriptor.ofRequired(
                        "path",
                        "Target Folder / File",
                        TaskParameterType.FILE,
                        "",
                        "Select a folder or single file to analyze."
                ),
                TaskParameterDescriptor.of(
                        "extensions",
                        "File Extensions",
                        TaskParameterType.STRING,
                        ".txt,.md,.java,.xml,.json,.properties,.log,.csv",
                        "Comma-separated extensions to include (empty for all files)."
                ),
                TaskParameterDescriptor.of(
                        "topN",
                        "Top Keywords Count",
                        TaskParameterType.INTEGER,
                        15,
                        "Number of highest-frequency words to extract."
                ),
                TaskParameterDescriptor.of(
                        "minWordLength",
                        "Minimum Word Length",
                        TaskParameterType.INTEGER,
                        3,
                        "Exclude words shorter than this length."
                ),
                TaskParameterDescriptor.of(
                        "ignoreStopWords",
                        "Filter Common Stop Words",
                        TaskParameterType.BOOLEAN,
                        true,
                        "Exclude trivial English words (e.g. 'the', 'and', 'with')."
                ),
                TaskParameterDescriptor.of(
                        "caseSensitive",
                        "Case Sensitive",
                        TaskParameterType.BOOLEAN,
                        false,
                        "Whether to treat capitalized words separately."
                )
        );
    }

    @Override
    public TaskExecutionResult execute(TaskContext context, Map<String, Object> parameters) throws Exception {
        long startTime = System.currentTimeMillis();
        context.log("Starting Text & Code Word Frequency Counter plugin...");

        String rawPath = (String) parameters.get("path");
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Target path must be specified.");
        }

        Path target = Path.of(rawPath);
        if (!Files.exists(target)) {
            throw new IllegalArgumentException("Target path does not exist: " + target);
        }

        String rawExt = (String) parameters.getOrDefault("extensions", "");
        Set<String> allowedExtensions = parseExtensions(rawExt);

        int topN = getIntParam(parameters, "topN", 15);
        int minLength = getIntParam(parameters, "minWordLength", 3);
        boolean filterStopWords = getBoolParam(parameters, "ignoreStopWords", true);
        boolean caseSensitive = getBoolParam(parameters, "caseSensitive", false);

        context.log("Scanning target: " + target.toAbsolutePath());
        context.updateIndeterminate();
        context.updateMessage("Discovering files...");

        List<Path> filesToAnalyze = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            filesToAnalyze.add(target);
        } else {
            try (Stream<Path> stream = Files.walk(target)) {
                filesToAnalyze = stream.filter(Files::isRegularFile)
                        .filter(p -> matchesExtension(p, allowedExtensions))
                        .collect(Collectors.toList());
            }
        }

        int totalFiles = filesToAnalyze.size();
        context.log("Found " + totalFiles + " candidate file(s) matching criteria.");

        if (totalFiles == 0) {
            return TaskExecutionResult.success(0, 0, "No matching files found to analyze.", Collections.emptyMap(), System.currentTimeMillis() - startTime);
        }

        Map<String, Long> wordFrequencies = new HashMap<>();
        long totalWords = 0;
        long totalLines = 0;
        long totalBytes = 0;

        for (int i = 0; i < totalFiles; i++) {
            context.checkPauseAndCancel();

            Path file = filesToAnalyze.get(i);
            context.updateProgress(i, totalFiles);
            context.updateMessage("Processing (" + (i + 1) + "/" + totalFiles + "): " + file.getFileName());

            long fileSize = 0;
            try {
                fileSize = Files.size(file);
                totalBytes += fileSize;
            } catch (IOException ignored) {}

            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                long lineInFile = 0;
                while ((line = reader.readLine()) != null) {
                    lineInFile++;
                    if (lineInFile % 500 == 0) {
                        context.checkPauseAndCancel();
                    }

                    totalLines++;
                    String[] tokens = line.split("[^a-zA-Z0-9_]+");
                    for (String token : tokens) {
                        if (token.length() < minLength) continue;

                        String word = caseSensitive ? token : token.toLowerCase(Locale.ROOT);
                        if (filterStopWords && STOP_WORDS.contains(word.toLowerCase(Locale.ROOT))) {
                            continue;
                        }

                        wordFrequencies.merge(word, 1L, Long::sum);
                        totalWords++;
                    }
                }
            } catch (Exception ex) {
                context.logWarn("Could not parse file " + file.getFileName() + ": " + ex.getMessage());
            }
        }

        context.checkPauseAndCancel();
        context.updateProgress(totalFiles, totalFiles);
        context.updateMessage("Aggregating metrics...");

        // Sort top N words
        List<Map.Entry<String, Long>> sortedWords = wordFrequencies.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .toList();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalFiles", totalFiles);
        details.put("totalLines", totalLines);
        details.put("totalWords", totalWords);
        details.put("uniqueWords", wordFrequencies.size());
        details.put("totalBytes", totalBytes);

        List<String> topWordsList = new ArrayList<>();
        for (Map.Entry<String, Long> entry : sortedWords) {
            topWordsList.add(entry.getKey() + ": " + entry.getValue());
        }
        details.put("topWords", topWordsList);

        long durationMs = System.currentTimeMillis() - startTime;
        String summary = String.format(Locale.ROOT,
                "Scanned %d file(s) (%d lines, %d words, %d unique). Top word: '%s'",
                totalFiles, totalLines, totalWords, wordFrequencies.size(),
                sortedWords.isEmpty() ? "N/A" : (sortedWords.get(0).getKey() + " (" + sortedWords.get(0).getValue() + "x)"));

        context.log("Analysis completed successfully in " + durationMs + " ms. " + summary);
        return TaskExecutionResult.success(totalFiles, totalBytes, summary, details, durationMs);
    }

    private static Set<String> parseExtensions(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptySet();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.startsWith(".") ? s.toLowerCase(Locale.ROOT) : "." + s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private static boolean matchesExtension(Path path, Set<String> allowed) {
        if (allowed.isEmpty()) return true;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(name::endsWith);
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

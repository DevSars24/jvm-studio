package com.jvmstudio.core.db;

import com.jvmstudio.core.model.TaskSchedule;
import com.jvmstudio.core.model.TaskScheduleType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists and restores task configuration parameters and scheduling rules.
 */
public class TaskConfigRepository {
    private static final Logger logger = LoggerFactory.getLogger(TaskConfigRepository.class);
    private final DatabaseManager dbManager;

    public record PersistedTaskConfig(
            String taskId,
            Map<String, String> stringParams,
            TaskSchedule schedule
    ) {}

    public TaskConfigRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void saveConfig(String taskId, Map<String, Object> parameters, TaskSchedule schedule) {
        String serializedParams = serializeParams(parameters);
        String sql = """
            INSERT INTO task_configs (task_id, parameters_json, schedule_type, delay_seconds, schedule_enabled, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(task_id) DO UPDATE SET
                parameters_json = excluded.parameters_json,
                schedule_type = excluded.schedule_type,
                delay_seconds = excluded.delay_seconds,
                schedule_enabled = excluded.schedule_enabled,
                updated_at = excluded.updated_at
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, serializedParams);
            ps.setString(3, schedule.type().name());
            ps.setLong(4, schedule.delaySeconds());
            ps.setInt(5, schedule.enabled() ? 1 : 0);
            ps.setString(6, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to save task config for {}", taskId, e);
        }
    }

    public PersistedTaskConfig findConfig(String taskId) {
        String sql = "SELECT * FROM task_configs WHERE task_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String rawParams = rs.getString("parameters_json");
                    String schedTypeStr = rs.getString("schedule_type");
                    long delaySec = rs.getLong("delay_seconds");
                    boolean enabled = rs.getInt("schedule_enabled") == 1;

                    TaskScheduleType schedType = TaskScheduleType.MANUAL;
                    try {
                        if (schedTypeStr != null) schedType = TaskScheduleType.valueOf(schedTypeStr);
                    } catch (Exception ignored) {}

                    TaskSchedule schedule = new TaskSchedule(schedType, delaySec, enabled);
                    Map<String, String> params = deserializeParams(rawParams);
                    return new PersistedTaskConfig(taskId, params, schedule);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to load task config for {}", taskId, e);
        }
        return null;
    }

    private static String serializeParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() != null) {
                String key = encode(entry.getKey());
                String val = encode(String.valueOf(entry.getValue()));
                sb.append(key).append("=").append(val).append("\n");
            }
        }
        return sb.toString();
    }

    private static Map<String, String> deserializeParams(String raw) {
        Map<String, String> map = new HashMap<>();
        if (raw == null || raw.isBlank()) return map;
        String[] lines = raw.split("\n");
        for (String line : lines) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                String k = decode(line.substring(0, eq));
                String v = decode(line.substring(eq + 1));
                map.put(k, v);
            }
        }
        return map;
    }

    private static String encode(String s) {
        return s.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
    }

    private static String decode(String s) {
        return s.replace("\\=", "=").replace("\\n", "\n").replace("\\\\", "\\");
    }
}

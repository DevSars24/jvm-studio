package com.jvmstudio.core.db;

import com.jvmstudio.core.model.TaskHistoryRecord;
import com.jvmstudio.core.model.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for managing execution history persistence and CSV export.
 */
public class TaskHistoryRepository {
    private static final Logger logger = LoggerFactory.getLogger(TaskHistoryRepository.class);
    private final DatabaseManager dbManager;

    public TaskHistoryRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void insert(TaskHistoryRecord record) {
        String sql = """
            INSERT INTO task_history (
                execution_id, task_id, task_name, category, start_time, end_time,
                duration_ms, state, items_processed, bytes_processed, summary,
                error_message, log_snippet
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.executionId());
            ps.setString(2, record.taskId());
            ps.setString(3, record.taskName());
            ps.setString(4, record.category());
            ps.setString(5, record.startTime() != null ? record.startTime().toString() : Instant.now().toString());
            ps.setString(6, record.endTime() != null ? record.endTime().toString() : null);
            ps.setLong(7, record.durationMs());
            ps.setString(8, record.state().name());
            ps.setLong(9, record.itemsProcessed());
            ps.setLong(10, record.bytesProcessed());
            ps.setString(11, record.summary());
            ps.setString(12, record.errorMessage());
            ps.setString(13, record.logSnippet());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to insert task history record {}", record.executionId(), e);
        }
    }

    public void update(TaskHistoryRecord record) {
        String sql = """
            UPDATE task_history SET
                end_time = ?,
                duration_ms = ?,
                state = ?,
                items_processed = ?,
                bytes_processed = ?,
                summary = ?,
                error_message = ?,
                log_snippet = ?
            WHERE execution_id = ?
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.endTime() != null ? record.endTime().toString() : null);
            ps.setLong(2, record.durationMs());
            ps.setString(3, record.state().name());
            ps.setLong(4, record.itemsProcessed());
            ps.setLong(5, record.bytesProcessed());
            ps.setString(6, record.summary());
            ps.setString(7, record.errorMessage());
            ps.setString(8, record.logSnippet());
            ps.setString(9, record.executionId());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to update task history record {}", record.executionId(), e);
        }
    }

    public List<TaskHistoryRecord> findFiltered(String taskIdFilter, TaskState stateFilter, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT * FROM task_history WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (taskIdFilter != null && !taskIdFilter.isBlank()) {
            sql.append(" AND task_id = ? ");
            params.add(taskIdFilter.trim());
        }
        if (stateFilter != null) {
            sql.append(" AND state = ? ");
            params.add(stateFilter.name());
        }
        sql.append(" ORDER BY start_time DESC LIMIT ? OFFSET ?");
        params.add(limit <= 0 ? 100 : limit);
        params.add(Math.max(0, offset));

        List<TaskHistoryRecord> results = new ArrayList<>();
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    results.add(mapRecord(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to query task history", e);
        }
        return results;
    }

    public List<TaskHistoryRecord> findAllRecent(int limit) {
        return findFiltered(null, null, limit, 0);
    }

    public void deleteOlderThan(int days) {
        if (days <= 0) return;
        Instant cutoff = Instant.now().minusSeconds(days * 86400L);
        String sql = "DELETE FROM task_history WHERE start_time < ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, cutoff.toString());
            int count = ps.executeUpdate();
            if (count > 0) {
                logger.info("Purged {} expired history records older than {} days", count, days);
            }
        } catch (SQLException e) {
            logger.error("Failed to delete expired history records", e);
        }
    }

    public void clearAll() {
        String sql = "DELETE FROM task_history";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to clear task history", e);
        }
    }

    /**
     * Requirement: Export history to CSV with correct RFC-4180 escaping.
     */
    public void exportToCsv(Writer writer, List<TaskHistoryRecord> records) throws IOException {
        writer.write("Execution ID,Task ID,Task Name,Category,Start Time,End Time,Duration (ms),State,Items Processed,Bytes Processed,Summary,Error Message\r\n");
        for (TaskHistoryRecord r : records) {
            writer.write(escapeCsv(r.executionId()));
            writer.write(",");
            writer.write(escapeCsv(r.taskId()));
            writer.write(",");
            writer.write(escapeCsv(r.taskName()));
            writer.write(",");
            writer.write(escapeCsv(r.category()));
            writer.write(",");
            writer.write(escapeCsv(r.startTime() != null ? r.startTime().toString() : ""));
            writer.write(",");
            writer.write(escapeCsv(r.endTime() != null ? r.endTime().toString() : ""));
            writer.write(",");
            writer.write(String.valueOf(r.durationMs()));
            writer.write(",");
            writer.write(escapeCsv(r.state() != null ? r.state().name() : ""));
            writer.write(",");
            writer.write(String.valueOf(r.itemsProcessed()));
            writer.write(",");
            writer.write(String.valueOf(r.bytesProcessed()));
            writer.write(",");
            writer.write(escapeCsv(r.summary()));
            writer.write(",");
            writer.write(escapeCsv(r.errorMessage()));
            writer.write("\r\n");
        }
        writer.flush();
    }

    public static String escapeCsv(String value) {
        if (value == null) return "";
        boolean containsSpecial = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!containsSpecial) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private TaskHistoryRecord mapRecord(ResultSet rs) throws SQLException {
        String startTimeStr = rs.getString("start_time");
        String endTimeStr = rs.getString("end_time");
        String stateStr = rs.getString("state");

        Instant start = startTimeStr != null ? Instant.parse(startTimeStr) : Instant.now();
        Instant end = endTimeStr != null ? Instant.parse(endTimeStr) : null;
        TaskState state = TaskState.COMPLETED;
        try {
            if (stateStr != null) state = TaskState.valueOf(stateStr);
        } catch (IllegalArgumentException ignored) {}

        return new TaskHistoryRecord(
                rs.getString("execution_id"),
                rs.getString("task_id"),
                rs.getString("task_name"),
                rs.getString("category"),
                start,
                end,
                rs.getLong("duration_ms"),
                state,
                rs.getLong("items_processed"),
                rs.getLong("bytes_processed"),
                rs.getString("summary"),
                rs.getString("error_message"),
                rs.getString("log_snippet")
        );
    }
}

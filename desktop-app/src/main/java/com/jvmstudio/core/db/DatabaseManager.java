package com.jvmstudio.core.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages SQLite database connection and versioned schema lifecycle.
 * By default, stores the database in the user home directory at ~/.jvmstudio/jvmstudio.db.
 */
public class DatabaseManager implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static final int CURRENT_SCHEMA_VERSION = 1;

    private final Path dbPath;
    private final String jdbcUrl;
    private Connection connection;

    public DatabaseManager() {
        this(resolveDefaultDbPath());
    }

    public DatabaseManager(Path dbPath) {
        this.dbPath = dbPath;
        this.jdbcUrl = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        initialize();
    }

    public static Path resolveDefaultDbPath() {
        String userHome = System.getProperty("user.home", ".");
        Path appDir = Path.of(userHome, ".jvmstudio");
        try {
            Files.createDirectories(appDir);
        } catch (IOException e) {
            logger.warn("Could not create app dir at {}, falling back to local dir", appDir, e);
            return Path.of("jvmstudio.db");
        }
        return appDir.resolve("jvmstudio.db");
    }

    public Path getDbPath() {
        return dbPath;
    }

    public synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL;");
                st.execute("PRAGMA foreign_keys = ON;");
            }
        }
        return connection;
    }

    private void initialize() {
        try {
            if (dbPath.getParent() != null) {
                Files.createDirectories(dbPath.getParent());
            }
            try (Connection conn = getConnection()) {
                int version = getSchemaVersion(conn);
                if (version < CURRENT_SCHEMA_VERSION) {
                    applyMigrations(conn, version);
                    setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                }
                markInterruptedRuns(conn);
            }
            logger.info("Database initialized successfully at {}", dbPath.toAbsolutePath());
        } catch (Exception e) {
            logger.error("Failed to initialize database at {}", dbPath, e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private int getSchemaVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private void setSchemaVersion(Connection conn, int version) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = " + version + ";");
        }
    }

    private void applyMigrations(Connection conn, int currentVersion) throws SQLException {
        if (currentVersion < 1) {
            try (Statement st = conn.createStatement()) {
                // Task execution history table
                st.execute("""
                    CREATE TABLE IF NOT EXISTS task_history (
                        execution_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        task_name TEXT NOT NULL,
                        category TEXT,
                        start_time TEXT NOT NULL,
                        end_time TEXT,
                        duration_ms INTEGER DEFAULT 0,
                        state TEXT NOT NULL,
                        items_processed INTEGER DEFAULT 0,
                        bytes_processed INTEGER DEFAULT 0,
                        summary TEXT,
                        error_message TEXT,
                        log_snippet TEXT
                    );
                """);
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_history_task_id ON task_history(task_id);");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_history_start ON task_history(start_time);");
                st.execute("CREATE INDEX IF NOT EXISTS idx_task_history_state ON task_history(state);");

                // Task configuration table
                st.execute("""
                    CREATE TABLE IF NOT EXISTS task_configs (
                        task_id TEXT PRIMARY KEY,
                        parameters_json TEXT NOT NULL,
                        schedule_type TEXT NOT NULL,
                        delay_seconds INTEGER DEFAULT 0,
                        schedule_enabled INTEGER DEFAULT 0,
                        updated_at TEXT NOT NULL
                    );
                """);

                // Plugin registry table
                st.execute("""
                    CREATE TABLE IF NOT EXISTS plugin_registry (
                        plugin_id TEXT PRIMARY KEY,
                        plugin_name TEXT NOT NULL,
                        version TEXT,
                        author TEXT,
                        api_version TEXT,
                        jar_path TEXT NOT NULL,
                        installed_at TEXT NOT NULL
                    );
                """);

                // Application settings table
                st.execute("""
                    CREATE TABLE IF NOT EXISTS app_settings (
                        setting_key TEXT PRIMARY KEY,
                        setting_value TEXT NOT NULL
                    );
                """);
            }
            logger.info("Applied migration to schema version 1.");
        }
    }

    /**
     * Requirement: On restart, mark previously interrupted runs appropriately.
     * Do not leave tasks stuck in RUNNING or PAUSED.
     */
    private void markInterruptedRuns(Connection conn) throws SQLException {
        String sql = """
            UPDATE task_history
            SET state = 'FAILED',
                summary = 'Execution interrupted by unexpected application restart or shutdown',
                error_message = 'Interrupted: process terminated before task completed'
            WHERE state IN ('RUNNING', 'PAUSED')
        """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int rows = ps.executeUpdate();
            if (rows > 0) {
                logger.warn("Marked {} previously active task(s) as INTERRUPTED/FAILED", rows);
            }
        }
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException e) {
                logger.warn("Error closing database connection", e);
            }
        }
    }
}

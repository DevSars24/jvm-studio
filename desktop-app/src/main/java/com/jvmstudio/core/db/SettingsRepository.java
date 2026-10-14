package com.jvmstudio.core.db;

import com.jvmstudio.core.model.AppSettings;
import com.jvmstudio.core.model.CollisionPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists and loads application settings to/from SQLite.
 */
public class SettingsRepository {
    private static final Logger logger = LoggerFactory.getLogger(SettingsRepository.class);
    private final DatabaseManager dbManager;

    public SettingsRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public AppSettings loadSettings() {
        AppSettings defaults = AppSettings.defaultSettings();
        Map<String, String> map = new HashMap<>();

        String sql = "SELECT setting_key, setting_value FROM app_settings";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString("setting_key"), rs.getString("setting_value"));
            }
        } catch (SQLException e) {
            logger.warn("Could not load app settings, returning defaults", e);
            return defaults;
        }

        if (map.isEmpty()) {
            return defaults;
        }

        int poolSize = parseInt(map.get("threadPoolSize"), defaults.threadPoolSize());
        int samplingInterval = parseInt(map.get("jvmSamplingIntervalMs"), defaults.jvmSamplingIntervalMs());
        CollisionPolicy collision = parseEnum(map.get("defaultCollisionPolicy"), defaults.defaultCollisionPolicy());
        int retention = parseInt(map.get("historyRetentionDays"), defaults.historyRetentionDays());
        boolean dark = parseBoolean(map.get("darkTheme"), defaults.darkTheme());
        boolean autoScroll = parseBoolean(map.get("autoScrollLogs"), defaults.autoScrollLogs());

        return new AppSettings(poolSize, samplingInterval, collision, retention, dark, autoScroll);
    }

    public void saveSettings(AppSettings settings) {
        String sql = """
            INSERT INTO app_settings (setting_key, setting_value) VALUES (?, ?)
            ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value
        """;
        try (Connection conn = dbManager.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                put(ps, "threadPoolSize", String.valueOf(settings.threadPoolSize()));
                put(ps, "jvmSamplingIntervalMs", String.valueOf(settings.jvmSamplingIntervalMs()));
                put(ps, "defaultCollisionPolicy", settings.defaultCollisionPolicy().name());
                put(ps, "historyRetentionDays", String.valueOf(settings.historyRetentionDays()));
                put(ps, "darkTheme", String.valueOf(settings.darkTheme()));
                put(ps, "autoScrollLogs", String.valueOf(settings.autoScrollLogs()));
                conn.commit();
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            logger.error("Failed to save app settings", e);
        }
    }

    private void put(PreparedStatement ps, String key, String value) throws SQLException {
        ps.setString(1, key);
        ps.setString(2, value);
        ps.executeUpdate();
    }

    private static int parseInt(String val, int def) {
        if (val == null) return def;
        try { return Integer.parseInt(val); } catch (Exception e) { return def; }
    }

    private static boolean parseBoolean(String val, boolean def) {
        if (val == null) return def;
        return Boolean.parseBoolean(val);
    }

    private static <T extends Enum<T>> T parseEnum(String val, T def) {
        if (val == null) return def;
        try {
            return Enum.valueOf(def.getDeclaringClass(), val);
        } catch (Exception e) {
            return def;
        }
    }
}

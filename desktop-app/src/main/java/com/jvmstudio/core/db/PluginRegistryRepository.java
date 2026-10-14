package com.jvmstudio.core.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists dynamically loaded plugin metadata and JAR file paths.
 */
public class PluginRegistryRepository {
    private static final Logger logger = LoggerFactory.getLogger(PluginRegistryRepository.class);
    private final DatabaseManager dbManager;

    public record PluginEntry(
            String pluginId,
            String pluginName,
            String version,
            String author,
            String apiVersion,
            Path jarPath,
            Instant installedAt,
            boolean fileExists
    ) {}

    public PluginRegistryRepository(DatabaseManager dbManager) {
        this.dbManager = dbManager;
    }

    public void registerPlugin(String pluginId, String pluginName, String version, String author, String apiVersion, Path jarPath) {
        String sql = """
            INSERT INTO plugin_registry (plugin_id, plugin_name, version, author, api_version, jar_path, installed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(plugin_id) DO UPDATE SET
                plugin_name = excluded.plugin_name,
                version = excluded.version,
                author = excluded.author,
                api_version = excluded.api_version,
                jar_path = excluded.jar_path,
                installed_at = excluded.installed_at
        """;
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pluginId);
            ps.setString(2, pluginName);
            ps.setString(3, version);
            ps.setString(4, author);
            ps.setString(5, apiVersion);
            ps.setString(6, jarPath.toAbsolutePath().toString());
            ps.setString(7, Instant.now().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to register plugin in database: {}", pluginId, e);
        }
    }

    public void unregisterPlugin(String pluginId) {
        String sql = "DELETE FROM plugin_registry WHERE plugin_id = ?";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pluginId);
            ps.executeUpdate();
        } catch (SQLException e) {
            logger.error("Failed to unregister plugin: {}", pluginId, e);
        }
    }

    /**
     * Requirement: Validate saved plugin paths before offering to reload them.
     */
    public List<PluginEntry> listSavedPlugins() {
        List<PluginEntry> list = new ArrayList<>();
        String sql = "SELECT * FROM plugin_registry ORDER BY installed_at DESC";
        try (Connection conn = dbManager.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String pathStr = rs.getString("jar_path");
                Path path = Path.of(pathStr);
                boolean exists = Files.exists(path) && Files.isRegularFile(path);
                String installedStr = rs.getString("installed_at");
                Instant installedAt = installedStr != null ? Instant.parse(installedStr) : Instant.now();

                list.add(new PluginEntry(
                        rs.getString("plugin_id"),
                        rs.getString("plugin_name"),
                        rs.getString("version"),
                        rs.getString("author"),
                        rs.getString("api_version"),
                        path,
                        installedAt,
                        exists
                ));
            }
        } catch (SQLException e) {
            logger.error("Failed to list saved plugins", e);
        }
        return list;
    }
}

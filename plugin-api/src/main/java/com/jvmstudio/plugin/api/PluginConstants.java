package com.jvmstudio.plugin.api;

/**
 * Constants defining the Plugin API version contract and compatibility bounds.
 */
public final class PluginConstants {
    private PluginConstants() {}

    /**
     * The current major.minor API version implemented by this library.
     */
    public static final String CURRENT_API_VERSION = "1.0";

    /**
     * Maximum supported API major version.
     */
    public static final int CURRENT_MAJOR_VERSION = 1;

    /**
     * Check if a plugin's target API version is compatible with this host runtime.
     * Backwards-compatible within the same major version.
     */
    public static boolean isCompatible(String pluginApiVersion) {
        if (pluginApiVersion == null || pluginApiVersion.isBlank()) {
            return false;
        }
        try {
            String[] parts = pluginApiVersion.trim().split("\\.");
            int major = Integer.parseInt(parts[0]);
            return major == CURRENT_MAJOR_VERSION;
        } catch (Exception e) {
            return false;
        }
    }
}

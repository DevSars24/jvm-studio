package com.jvmstudio.core.model;

/**
 * File collision resolution policies for file operations (e.g. File Backup).
 */
public enum CollisionPolicy {
    OVERWRITE("Overwrite existing file"),
    SKIP("Skip file (keep existing)"),
    RENAME_TIMESTAMP("Rename with timestamp suffix");

    private final String description;

    CollisionPolicy(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}

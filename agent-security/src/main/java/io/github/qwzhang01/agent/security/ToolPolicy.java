package io.github.qwzhang01.agent.security;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tool permission policy: maps tool names to {@link ToolPermission} (Stage 9 D2).
 * <p>
 * Provides a default permission for tools not explicitly registered, and allows
 * runtime permission changes (e.g. an admin can downgrade a tool from AUTO to
 * REQUIRES_APPROVAL after an incident).
 */
public class ToolPolicy {

    private final Map<String, ToolPermission> toolPermissions = new ConcurrentHashMap<>();
    private final ToolPermission defaultPermission;

    /**
     * @param defaultPermission permission for tools not explicitly registered.
     *                          Recommended: {@link ToolPermission#AUTO} for development,
     *                          {@link ToolPermission#REQUIRES_APPROVAL} for production.
     */
    public ToolPolicy(ToolPermission defaultPermission) {
        this.defaultPermission = Objects.requireNonNull(defaultPermission);
    }

    /**
     * Look up the permission for a tool.
     */
    public ToolPermission permissionFor(String toolName) {
        return toolPermissions.getOrDefault(toolName, defaultPermission);
    }

    /**
     * Register or update a tool's permission.
     */
    public ToolPolicy setPermission(String toolName, ToolPermission permission) {
        toolPermissions.put(toolName, permission);
        return this;
    }

    /**
     * Bulk-set permissions from a map.
     */
    public ToolPolicy setAll(Map<String, ToolPermission> permissions) {
        toolPermissions.putAll(permissions);
        return this;
    }

    /**
     * Remove a tool's explicit permission (falls back to default).
     */
    public ToolPolicy removePermission(String toolName) {
        toolPermissions.remove(toolName);
        return this;
    }

    public ToolPermission getDefaultPermission() {
        return defaultPermission;
    }

    /**
     * All explicitly registered permissions (for inspection / audit).
     */
    public Map<String, ToolPermission> getAllPermissions() {
        return Map.copyOf(toolPermissions);
    }
}

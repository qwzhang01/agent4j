package io.github.qwzhang01.agent.plugin;

/**
 * Metadata describing a plugin.
 * <p>
 * Stored in the plugin's {@link Plugin#descriptor()} method.
 * Used by PluginManager for logging, display, and dependency resolution (future).
 *
 * @param name        unique plugin identifier (e.g. "search-tool")
 * @param version     semantic version (e.g. "1.0.0")
 * @param description human-readable description
 */
public record PluginDescriptor(
        String name,
        String version,
        String description
) {
}

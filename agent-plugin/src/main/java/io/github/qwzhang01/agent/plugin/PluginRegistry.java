package io.github.qwzhang01.agent.plugin;

import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Manages the lifecycle of loaded plugins.
 * <p>
 * Responsibilities:
 * - Track loaded plugins and their states
 * - Execute onLoad/onUnload with error isolation
 * - Provide query APIs for status inspection
 * <p>
 * Design:
 * - One plugin failure does NOT affect others
 * - onLoad failure -> state = FAILED, exception stored
 * - onUnload failure -> state = UNLOADED anyway, error logged
 * <p>
 * This class is NOT thread-safe for Stage 3.
 * Concurrent load/unload will be addressed in Stage 6+.
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final ToolRegistry toolRegistry;
    private final Map<String, PluginEntry> plugins = new LinkedHashMap<>();

    /**
     * @param toolRegistry the tool registry that plugins will register tools into
     */
    public PluginRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    // ============ Load / Unload ============

    /**
     * Load a plugin: call onLoad, track state.
     * <p>
     * If onLoad throws, the plugin state becomes FAILED and the exception
     * is stored. Other plugins are not affected.
     *
     * @param plugin the plugin to load
     * @throws PluginException if the plugin name is already in use
     */
    public void load(Plugin plugin) {
        PluginDescriptor desc = plugin.descriptor();
        String name = desc.name();

        if (plugins.containsKey(name)) {
            PluginEntry existing = plugins.get(name);
            if (existing.state == PluginState.LOADED) {
                throw new PluginException(name, "Plugin already loaded");
            }
        }

        PluginContext context = new SimplePluginContext(toolRegistry);
        PluginEntry entry = new PluginEntry(plugin, context, PluginState.DETECTED, null);
        plugins.put(name, entry);

        try {
            log.info("Loading plugin: {} v{}", name, desc.version());
            plugin.onLoad(context);
            entry.state = PluginState.LOADED;
            log.info("Plugin loaded: {} v{}", name, desc.version());
        } catch (Exception e) {
            entry.state = PluginState.FAILED;
            entry.error = e;
            log.error("Plugin {} failed to load: {}", name, e.getMessage(), e);
            // Do NOT rethrow - isolate the failure
        }
    }

    /**
     * Unload a plugin by name: call onUnload, update state.
     * <p>
     * If onUnload throws, the plugin is still marked as UNLOADED
     * (best-effort cleanup) and the error is logged.
     *
     * @param name the plugin name
     * @throws PluginException if the plugin is not found
     */
    public void unload(String name) {
        PluginEntry entry = plugins.get(name);
        if (entry == null) {
            throw new PluginException(name, "Plugin not found");
        }

        if (entry.state != PluginState.LOADED) {
            throw new PluginException(name, "Plugin is not loaded (state: " + entry.state + ")");
        }

        try {
            log.info("Unloading plugin: {}", name);
            entry.plugin.onUnload(entry.context);
            log.info("Plugin unloaded: {}", name);
        } catch (Exception e) {
            log.error("Plugin {} failed to unload: {}", name, e.getMessage(), e);
            // Still mark as unloaded - best effort
        }

        entry.state = PluginState.UNLOADED;
    }

    /**
     * Reload a plugin: unload then load.
     * Useful for upgrading plugins at runtime.
     */
    public void reload(String name) {
        PluginEntry entry = plugins.get(name);
        if (entry == null) {
            throw new PluginException(name, "Plugin not found");
        }
        unload(name);
        load(entry.plugin);
    }

    // ============ Query ============

    /**
     * Get a plugin's current state.
     */
    public Optional<PluginState> getState(String name) {
        PluginEntry entry = plugins.get(name);
        return entry != null ? Optional.of(entry.state) : Optional.empty();
    }

    /**
     * Get all loaded plugins (state = LOADED).
     */
    public List<Plugin> getLoadedPlugins() {
        return plugins.values().stream()
                .filter(e -> e.state == PluginState.LOADED)
                .map(e -> e.plugin)
                .toList();
    }

    /**
     * List all plugins with their states.
     */
    public List<PluginInfo> listPlugins() {
        return plugins.values().stream()
                .map(e -> new PluginInfo(
                        e.plugin.descriptor(),
                        e.state,
                        e.error != null ? e.error.getMessage() : null
                ))
                .toList();
    }

    // ============ Inner ============

    /**
     * Simple PluginContext that only wraps a ToolRegistry.
     */
    private record SimplePluginContext(ToolRegistry toolRegistry) implements PluginContext {
        @Override
        public ToolRegistry getToolRegistry() {
            return toolRegistry;
        }
    }

    /**
     * Internal entry tracking a plugin's state.
     */
    private static class PluginEntry {
        final Plugin plugin;
        final PluginContext context;
        PluginState state;
        Throwable error;

        PluginEntry(Plugin plugin, PluginContext context, PluginState state, Throwable error) {
            this.plugin = plugin;
            this.context = context;
            this.state = state;
            this.error = error;
        }
    }

    /**
     * Read-only snapshot of a plugin's status.
     */
    public record PluginInfo(
            PluginDescriptor descriptor,
            PluginState state,
            String error
    ) {}
}

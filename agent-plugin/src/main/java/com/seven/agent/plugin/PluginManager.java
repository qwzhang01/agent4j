package com.seven.agent.plugin;

import com.seven.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Discovers and manages plugins via Java SPI (ServiceLoader).
 * <p>
 * Responsibilities:
 * - Scan classpath for ToolPlugin implementations
 * - Batch load all discovered plugins
 * - Delegate lifecycle operations to PluginRegistry
 * <p>
 * Usage:
 * <pre>{@code
 * PluginManager manager = new PluginManager(toolRegistry);
 * manager.loadAll();               // scan + load all plugins
 * manager.unloadAll();             // unload all plugins
 * manager.load("search-tool");     // load specific plugin by name
 * manager.unload("search-tool");   // unload specific plugin
 * manager.listPlugins();           // show all plugins with states
 * }</pre>
 * <p>
 * Plugins are discovered via {@code META-INF/services/com.seven.agent.plugin.ToolPlugin}.
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginRegistry registry;
    private final Map<String, Plugin> discovered = new LinkedHashMap<>();

    /**
     * @param toolRegistry the tool registry that plugins will register tools into
     */
    public PluginManager(ToolRegistry toolRegistry) {
        this.registry = new PluginRegistry(toolRegistry);
    }

    // ============ Discovery ============

    /**
     * Scan classpath for ToolPlugin implementations via ServiceLoader.
     * <p>
     * Found plugins are stored in state DETECTED but not yet loaded.
     *
     * @return number of plugins discovered
     */
    public int discover() {
        ServiceLoader<ToolPlugin> loader = ServiceLoader.load(ToolPlugin.class);
        int count = 0;

        for (ToolPlugin plugin : loader) {
            String name = plugin.descriptor().name();
            if (!discovered.containsKey(name)) {
                discovered.put(name, plugin);
                count++;
                log.info("Discovered plugin: {} v{}", name, plugin.descriptor().version());
            }
        }

        return count;
    }

    /**
     * Get all discovered plugins (not necessarily loaded).
     */
    public List<Plugin> getDiscoveredPlugins() {
        return List.copyOf(discovered.values());
    }

    // ============ Batch Operations ============

    /**
     * Discover (if not already) and load all plugins.
     * <p>
     * Each plugin is loaded independently. If one fails,
     * others are still loaded.
     *
     * @return number of plugins successfully loaded
     */
    public int loadAll() {
        if (discovered.isEmpty()) {
            discover();
        }

        int loaded = 0;
        for (Plugin plugin : discovered.values()) {
            try {
                registry.load(plugin);
                if (registry.getState(plugin.descriptor().name())
                        .orElse(PluginState.FAILED) == PluginState.LOADED) {
                    loaded++;
                }
            } catch (Exception e) {
                log.error("Failed to load plugin {}: {}", plugin.descriptor().name(), e.getMessage());
            }
        }
        log.info("Loaded {}/{} plugins", loaded, discovered.size());
        return loaded;
    }

    /**
     * Unload all currently loaded plugins.
     *
     * @return number of plugins successfully unloaded
     */
    public int unloadAll() {
        int unloaded = 0;
        for (Plugin plugin : discovered.values()) {
            String name = plugin.descriptor().name();
            if (registry.getState(name).orElse(PluginState.UNLOADED) == PluginState.LOADED) {
                try {
                    registry.unload(name);
                    unloaded++;
                } catch (Exception e) {
                    log.error("Failed to unload plugin {}: {}", name, e.getMessage());
                }
            }
        }
        log.info("Unloaded {}/{} plugins", unloaded, discovered.size());
        return unloaded;
    }

    // ============ Single Plugin Operations ============

    /**
     * Load a specific plugin by name.
     *
     * @param name the plugin name
     */
    public void load(String name) {
        Plugin plugin = discovered.get(name);
        if (plugin == null) {
            throw new PluginException(name, "Plugin not discovered. Run discover() first.");
        }
        registry.load(plugin);
    }

    /**
     * Unload a specific plugin by name.
     *
     * @param name the plugin name
     */
    public void unload(String name) {
        registry.unload(name);
    }

    /**
     * Reload a plugin (unload + load).
     *
     * @param name the plugin name
     */
    public void reload(String name) {
        registry.reload(name);
    }

    // ============ Query ============

    /**
     * List all plugins with their current states.
     */
    public List<PluginRegistry.PluginInfo> listPlugins() {
        return registry.listPlugins();
    }

    /**
     * Get the underlying PluginRegistry.
     */
    public PluginRegistry getRegistry() {
        return registry;
    }
}

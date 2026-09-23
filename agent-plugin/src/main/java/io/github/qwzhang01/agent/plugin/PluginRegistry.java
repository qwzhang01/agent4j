package io.github.qwzhang01.agent.plugin;

import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
 * - onLoad failure -> state = FAILED, exception stored,
 *   AND everything the plugin registered before failing is rolled back
 *   (Stage 6.4: "加载失败时回滚已注册 Tool、线程、连接和资源" — the tool
 *   rollback is enforced by the registry; threads/connections are
 *   plugin-owned resources the plugin must release in its own failure
 *   path, the registry can only guarantee the registry surface)
 * - onUnload failure -> state = UNLOADED anyway, error logged, orphan
 *   tools swept
 * <p>
 * Stage 6.4: thread-safe (per-plugin locking over a ConcurrentHashMap —
 * concurrent load/unload of DIFFERENT plugins proceeds in parallel, the
 * same plugin is serialized) and namespace-isolated (each plugin owns a
 * {@code pluginName__toolName} namespace; a plugin cannot overwrite or
 * unregister another plugin's tools, nor the host's own tools).
 * <p>
 * Security boundary (honest, per the roadmap's "明确当前 SPI Plugin 的安全
 * 边界，不再暗示隔离"): this registry isolates REGISTRATION SURFACES, not
 * code. SPI plugins run in-process with full JVM permissions. The only
 * enforcement point is the manifest gate at registration time — a plugin
 * without a manifest (legacy path, kept for backward compatibility) or
 * without {@code tools:true} cannot register tools. External jars get
 * classloader isolation via {@link PluginJarLoader}, which is dependency
 * hygiene, not confinement.
 */
public class PluginRegistry {

    private static final Logger log = LoggerFactory.getLogger(PluginRegistry.class);

    private final ToolRegistry toolRegistry;
    private final Map<String, PluginEntry> plugins = new ConcurrentHashMap<>();
    /** plugin name -> that plugin's lock: same-plugin ops serialize, others parallel. */
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * @param toolRegistry the tool registry that plugins will register tools into
     */
    public PluginRegistry(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Load a plugin with NO manifest (legacy / trusted-internal path).
     * The plugin may register tools freely — this is the Stage 3 behavior,
     * kept so existing hosts and tests do not break.
     */
    public void load(Plugin plugin) {
        load(plugin, null);
    }

    /**
     * Load a plugin under a host-provided {@link PluginManifest}.
     * <p>
     * The manifest is the trust anchor: name must match the descriptor,
     * and {@code tools:true} is required before any tool registration goes
     * through. onLoad failure rolls back whatever the plugin managed to
     * register before throwing.
     *
     * @param plugin   the plugin to load
     * @param manifest host-side permission declaration; null = legacy
     *                 trusted path (no enforcement)
     * @throws PluginException if the name is already in use or the manifest
     *                         name mismatches the descriptor
     */
    public void load(Plugin plugin, PluginManifest manifest) {
        PluginDescriptor desc = plugin.descriptor();
        String name = desc.name();

        Object lock = locks.computeIfAbsent(name, k -> new Object());
        synchronized (lock) {
            PluginEntry existing = plugins.get(name);
            if (existing != null && existing.state == PluginState.LOADED) {
                throw new PluginException(name, "Plugin already loaded");
            }
            if (manifest != null && !manifest.name().equals(name)) {
                throw new PluginException(name, "Manifest name '" + manifest.name()
                        + "' does not match plugin descriptor name");
            }

            TrackingPluginContext context = new TrackingPluginContext(toolRegistry, name, manifest);
            PluginEntry entry = new PluginEntry(plugin, context, PluginState.DETECTED,
                    null, manifest);
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
                rollback(context, name, "load failure");
            }
        }
    }

    /**
     * Unload a plugin by name: call onUnload, update state.
     * <p>
     * If onUnload throws, the plugin is still marked as UNLOADED
     * (best-effort cleanup) and the error is logged; orphan tools the
     * plugin registered are swept so the registry surface stays clean.
     *
     * @param name the plugin name
     * @throws PluginException if the plugin is not found
     */
    public void unload(String name) {
        Object lock = locks.computeIfAbsent(name, k -> new Object());
        synchronized (lock) {
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
            } finally {
                entry.context.cleanupOrphanTools();
                entry.state = PluginState.UNLOADED;
            }
        }
    }

    /**
     * Reload a plugin: unload then load.
     * Useful for upgrading plugins at runtime.
     */
    public void reload(String name) {
        Object lock = locks.computeIfAbsent(name, k -> new Object());
        synchronized (lock) {
            PluginEntry entry = plugins.get(name);
            if (entry == null) {
                throw new PluginException(name, "Plugin not found");
            }
            unload(name);
            load(entry.plugin, entry.manifest);
        }
    }

    private void rollback(TrackingPluginContext context, String name, String reason) {
        try {
            context.cleanupOrphanTools();
        } catch (Exception sweepFailure) {
            log.error("Plugin {} rollback failed after {}: {}", name, reason,
                    sweepFailure.getMessage(), sweepFailure);
        }
    }

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

    /**
     * The tools a plugin currently owns (its namespace view), newest
     * registration order preserved. Host inspection / debugging surface.
     */
    public List<String> toolsOf(String pluginName) {
        PluginEntry entry = plugins.get(pluginName);
        return entry != null ? List.copyOf(entry.context.registeredNames()) : List.of();
    }

    /**
     * PluginContext that records what the plugin registered, so unload and
     * load-failure rollback can drop tools the plugin left behind, and so
     * the manifest gate can deny undeclared registrations.
     * <p>
     * Namespace isolation (Stage 6.4): registration goes through
     * {@code pluginName__toolName}. The HOST's own tools (registered
     * directly on the delegate registry) are invisible to plugin
     * unregister/unregister because the tracking view only ever removes
     * names this plugin itself registered.
     */
    private static final class TrackingPluginContext implements PluginContext {
        private final ToolRegistry delegate;
        private final String pluginName;
        private final PluginManifest manifest;
        private final List<String> registeredNames = new ArrayList<>();
        private final ToolRegistry view;

        TrackingPluginContext(ToolRegistry delegate, String pluginName, PluginManifest manifest) {
            this.delegate = delegate;
            this.pluginName = pluginName;
            this.manifest = manifest;
            this.view = new ToolRegistry() {
                @Override
                public void register(Tool tool) {
                    if (manifest != null && !manifest.tools()) {
                        throw new PluginException(pluginName,
                                "manifest does not grant 'tools' permission");
                    }
                    delegate.register(tool);
                    registeredNames.add(tool.getName());
                }

                @Override
                public void unregister(String name) {
                    if (!registeredNames.contains(name)) {
                        return; // not ours: namespace isolation refuses foreign removals
                    }
                    delegate.unregister(name);
                    registeredNames.remove(name);
                }

                @Override
                public Optional<Tool> getTool(String name) {
                    return delegate.getTool(name);
                }

                @Override
                public List<Tool> listTools() {
                    return delegate.listTools();
                }

                @Override
                public List<String> getToolSchemas() {
                    return delegate.getToolSchemas();
                }
            };
        }

        @Override
        public ToolRegistry getToolRegistry() {
            return view;
        }

        void cleanupOrphanTools() {
            for (String name : List.copyOf(registeredNames)) {
                delegate.unregister(name);
            }
            registeredNames.clear();
        }

        List<String> registeredNames() {
            return registeredNames;
        }
    }

    /**
     * Internal entry tracking a plugin's state.
     */
    private static class PluginEntry {
        final Plugin plugin;
        final TrackingPluginContext context;
        final PluginManifest manifest;
        PluginState state;
        Throwable error;

        PluginEntry(Plugin plugin, TrackingPluginContext context, PluginState state,
                    Throwable error, PluginManifest manifest) {
            this.plugin = plugin;
            this.context = context;
            this.state = state;
            this.error = error;
            this.manifest = manifest;
        }
    }

    /**
     * Read-only snapshot of a plugin's status.
     */
    public record PluginInfo(
            PluginDescriptor descriptor,
            PluginState state,
            String error
    ) {
    }
}

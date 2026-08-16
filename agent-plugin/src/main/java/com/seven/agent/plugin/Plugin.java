package com.seven.agent.plugin;

/**
 * Core plugin interface.
 * <p>
 * A plugin is a self-contained unit that registers capabilities
 * (tools, model adapters, etc.) when loaded, and reverses those
 * registrations when unloaded.
 * <p>
 * Design principles:
 * - Registration is reversible: onLoad and onUnload are symmetric.
 * - Loading is isolated: if onLoad throws, other plugins are unaffected.
 * - Plugins are self-managing: they know what to register and unregister.
 * <p>
 * Use {@link ToolPlugin} for plugins that register tools.
 */
public interface Plugin {

    /**
     * Plugin metadata: name, version, description.
     */
    PluginDescriptor descriptor();

    /**
     * Called when the plugin is loaded.
     * <p>
     * The plugin should register its capabilities here.
     * If this method throws, the plugin state becomes FAILED
     * and other plugins are not affected.
     *
     * @param context the plugin context providing access to registries
     */
    void onLoad(PluginContext context);

    /**
     * Called when the plugin is unloaded.
     * <p>
     * The plugin should unregister everything it registered in onLoad.
     * If this method throws, the plugin is still marked as UNLOADED
     * and the error is logged.
     *
     * @param context the plugin context
     */
    void onUnload(PluginContext context);
}

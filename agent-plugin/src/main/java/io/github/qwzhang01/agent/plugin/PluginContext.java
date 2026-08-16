package io.github.qwzhang01.agent.plugin;

import io.github.qwzhang01.agent.core.tool.ToolRegistry;

/**
 * Context provided to plugins during load/unload.
 * <p>
 * Gives plugins access to framework registries so they can
 * register and unregister capabilities.
 * <p>
 * Currently only exposes ToolRegistry. Future stages will add
 * ModelClient registry, Memory store, Policy engine, etc.
 */
public interface PluginContext {

    /**
     * Get the tool registry.
     * Plugins use this to register/unregister tools.
     */
    ToolRegistry getToolRegistry();
}

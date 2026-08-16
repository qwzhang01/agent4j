package com.seven.agent.plugin;

/**
 * Lifecycle states of a plugin.
 * <p>
 * State transitions:
 * <pre>{@code
 *   DETECTED --load()--> LOADED --unload()--> UNLOADED --load()--> LOADED
 *                                    |
 *                          load() failed  --> FAILED
 * }</pre>
 * <p>
 * DETECTED: discovered by ServiceLoader but not yet loaded.
 * LOADED: onLoad succeeded, tools/capabilities are registered.
 * UNLOADED: onUnload completed, all registrations reversed.
 * FAILED: onLoad threw an exception, plugin is inert.
 */
public enum PluginState {
    DETECTED,
    LOADED,
    UNLOADED,
    FAILED
}

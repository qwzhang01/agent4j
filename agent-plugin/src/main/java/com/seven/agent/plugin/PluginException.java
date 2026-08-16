package com.seven.agent.plugin;

/**
 * Exception thrown when a plugin operation fails.
 * <p>
 * Used during load/unload to capture plugin-specific failures
 * without crashing the PluginManager or other plugins.
 */
public class PluginException extends RuntimeException {

    private final String pluginName;

    public PluginException(String pluginName, String message) {
        super("[" + pluginName + "] " + message);
        this.pluginName = pluginName;
    }

    public PluginException(String pluginName, String message, Throwable cause) {
        super("[" + pluginName + "] " + message, cause);
        this.pluginName = pluginName;
    }

    public String getPluginName() {
        return pluginName;
    }
}

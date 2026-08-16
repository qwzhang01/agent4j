package io.github.qwzhang01.agent.plugin.tools;

import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.plugin.PluginManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tool that lets the model unload a plugin by name.
 * <p>
 * The model calls this to remove a capability at runtime.
 * For example: "search-tool is giving wrong results" -> unload("search-tool").
 * <p>
 * Arguments:
 *   name: string - the plugin name to unload
 */
public class PluginUnloadTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PluginManager pluginManager;

    public PluginUnloadTool(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public String getName() {
        return "plugin_unload";
    }

    @Override
    public String getDescription() {
        return "Unload a plugin by name to remove its capabilities. "
                + "Use this when a plugin is no longer needed or is causing errors.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "name": "plugin_unload",
                    "description": "Unload a plugin to remove its capabilities",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name": {
                                "type": "string",
                                "description": "The plugin name to unload"
                            }
                        },
                        "required": ["name"]
                    }
                }
                """;
    }

    @Override
    public String execute(JsonNode arguments) {
        String name = arguments.path("name").asText("");

        if (name.isBlank()) {
            ObjectNode result = mapper.createObjectNode();
            result.put("success", false);
            result.put("error", "Plugin name is required");
            return result.toString();
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("plugin", name);

        try {
            pluginManager.unload(name);
            result.put("success", true);
            result.put("message", "Plugin '" + name + "' unloaded successfully");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result.toString();
    }
}

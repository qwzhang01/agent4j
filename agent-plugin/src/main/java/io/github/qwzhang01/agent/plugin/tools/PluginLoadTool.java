package io.github.qwzhang01.agent.plugin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.plugin.PluginManager;

/**
 * Tool that lets the model load a plugin by name.
 * <p>
 * The model calls this to gain a new capability at runtime.
 * For example: "I need to search the web" -> load("search-tool").
 * <p>
 * Arguments:
 * name: string - the plugin name to load
 */
public class PluginLoadTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PluginManager pluginManager;

    public PluginLoadTool(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public String getName() {
        return "plugin_load";
    }

    @Override
    public String getDescription() {
        return "Load a plugin by name to gain its capabilities. "
                + "Use plugin_list first to find available plugins.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "name": "plugin_load",
                    "description": "Load a plugin to gain new capabilities",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "name": {
                                "type": "string",
                                "description": "The plugin name to load"
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
            pluginManager.load(name);
            result.put("success", true);
            result.put("message", "Plugin '" + name + "' loaded successfully");
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result.toString();
    }
}

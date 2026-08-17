package io.github.qwzhang01.agent.plugin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.plugin.PluginManager;
import io.github.qwzhang01.agent.plugin.PluginRegistry;

import java.util.List;

/**
 * Tool that lets the model inspect its own runtime state:
 * which plugins are loaded, what tools are registered.
 * <p>
 * This is the "self-awareness" tool - the model calls this
 * to see what capabilities it currently has.
 * <p>
 * No arguments needed.
 */
public class PluginInspectTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PluginManager pluginManager;

    public PluginInspectTool(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public String getName() {
        return "plugin_inspect";
    }

    @Override
    public String getDescription() {
        return "Inspect the current runtime: which plugins are loaded, what tools are registered. "
                + "Call this when you need to know what capabilities you currently have.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "name": "plugin_inspect",
                    "description": "Inspect current plugin and tool state",
                    "parameters": {
                        "type": "object",
                        "properties": {}
                    }
                }
                """;
    }

    @Override
    public String execute(JsonNode arguments) {
        List<PluginRegistry.PluginInfo> plugins = pluginManager.listPlugins();

        ObjectNode result = mapper.createObjectNode();
        ArrayNode pluginArray = result.putArray("plugins");

        for (PluginRegistry.PluginInfo info : plugins) {
            ObjectNode pluginNode = pluginArray.addObject();
            pluginNode.put("name", info.descriptor().name());
            pluginNode.put("version", info.descriptor().version());
            pluginNode.put("description", info.descriptor().description());
            pluginNode.put("state", info.state().name());
            if (info.error() != null) {
                pluginNode.put("error", info.error());
            }
        }

        return result.toString();
    }
}

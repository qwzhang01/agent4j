package io.github.qwzhang01.agent.plugin.tools;

import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.plugin.Plugin;
import io.github.qwzhang01.agent.plugin.PluginManager;
import io.github.qwzhang01.agent.plugin.PluginRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/**
 * Tool that lets the model discover plugins that are available
 * but not yet loaded.
 * <p>
 * The model calls this to find out what additional capabilities
 * it could gain by loading a plugin.
 * <p>
 * No arguments needed.
 */
public class PluginListTool implements Tool {

    private static final ObjectMapper mapper = new ObjectMapper();

    private final PluginManager pluginManager;

    public PluginListTool(PluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public String getName() {
        return "plugin_list";
    }

    @Override
    public String getDescription() {
        return "List all discovered plugins and their states. "
                + "Call this to find plugins you could load to gain new capabilities.";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                    "name": "plugin_list",
                    "description": "List all discovered plugins with states",
                    "parameters": {
                        "type": "object",
                        "properties": {}
                    }
                }
                """;
    }

    @Override
    public String execute(JsonNode arguments) {
        List<Plugin> discovered = pluginManager.getDiscoveredPlugins();
        List<PluginRegistry.PluginInfo> infos = pluginManager.listPlugins();

        ObjectNode result = mapper.createObjectNode();
        ArrayNode pluginArray = result.putArray("plugins");

        for (PluginRegistry.PluginInfo info : infos) {
            ObjectNode pluginNode = pluginArray.addObject();
            pluginNode.put("name", info.descriptor().name());
            pluginNode.put("version", info.descriptor().version());
            pluginNode.put("description", info.descriptor().description());
            pluginNode.put("state", info.state().name());
        }

        result.put("total", discovered.size());
        result.put("available_to_load", infos.stream()
                .filter(i -> i.state() == io.github.qwzhang01.agent.plugin.PluginState.DETECTED
                        || i.state() == io.github.qwzhang01.agent.plugin.PluginState.UNLOADED
                        || i.state() == io.github.qwzhang01.agent.plugin.PluginState.FAILED)
                .count());

        return result.toString();
    }
}

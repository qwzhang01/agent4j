package com.seven.agent.examples.plugins;

import com.seven.agent.core.model.ChatRole;
import com.seven.agent.core.tool.Tool;
import com.seven.agent.plugin.PluginContext;
import com.seven.agent.plugin.PluginDescriptor;
import com.seven.agent.plugin.ToolPlugin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Example ToolPlugin that registers a "search_web" tool.
 * <p>
 * This plugin is discovered via SPI (see META-INF/services/).
 */
public class SearchToolPlugin implements ToolPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor("search-tool", "1.0.0", "Web search tool for querying information");
    }

    @Override
    public void onLoad(PluginContext context) {
        context.getToolRegistry().register(new SearchTool());
    }

    @Override
    public void onUnload(PluginContext context) {
        context.getToolRegistry().unregister("search_web");
    }

    // ============ The actual tool ============

    /**
     * A mock web search tool that returns canned results.
     */
    public static class SearchTool implements Tool {

        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String getName() {
            return "search_web";
        }

        @Override
        public String getDescription() {
            return "Search the web for information. Returns a list of relevant results.";
        }

        @Override
        public String getParametersSchema() {
            return """
                    {
                        "name": "search_web",
                        "description": "Search the web for information",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "query": {
                                    "type": "string",
                                    "description": "The search query"
                                }
                            },
                            "required": ["query"]
                        }
                    }
                    """;
        }

        @Override
        public String execute(JsonNode arguments) {
            String query = arguments.path("query").asText("unknown");
            return "Search results for '" + query + "': [1] Mock result 1, [2] Mock result 2";
        }
    }
}

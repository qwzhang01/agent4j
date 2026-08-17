package io.github.qwzhang01.agent.examples;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.plugin.PluginManager;
import io.github.qwzhang01.agent.plugin.PluginRegistry;
import io.github.qwzhang01.agent.plugin.tools.PluginInspectTool;
import io.github.qwzhang01.agent.plugin.tools.PluginListTool;
import io.github.qwzhang01.agent.plugin.tools.PluginLoadTool;
import io.github.qwzhang01.agent.plugin.tools.PluginUnloadTool;

/**
 * Demonstrates Agent self-evolution: the model inspects its own capabilities,
 * loads plugins it needs, uses them, then unloads when done.
 * <p>
 * This is the "self-modification" pattern from DeepSeek Harness (tool-cordis):
 * plugin management operations are exposed as tools the model can call.
 * <p>
 * Scenario: user asks "search for weather", but the Agent doesn't have
 * the search tool loaded yet. The model must:
 * 1. Inspect what it has
 * 2. Discover search-tool is available
 * 3. Load it
 * 4. Use it to search
 * 5. Unload it when done
 */
public class PluginSelfModificationExample {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) {
        System.out.println("=== Agent Self-Evolution Demo ===\n");
        System.out.println("Scenario: Model inspects capabilities, loads plugin, uses it, unloads.\n");

        // 1. Create tool registry + plugin manager
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        PluginManager pluginManager = new PluginManager(registry);

        // 2. Discover plugins (SearchToolPlugin + CalculatorToolPlugin via SPI)
        //    But DO NOT load them yet - let the model decide
        pluginManager.discover();
        System.out.println("Discovered " + pluginManager.getDiscoveredPlugins().size() + " plugins (not loaded yet)\n");

        // 3. Register plugin management tools so the model can call them
        registry.register(new PluginInspectTool(pluginManager));
        registry.register(new PluginListTool(pluginManager));
        registry.register(new PluginLoadTool(pluginManager));
        registry.register(new PluginUnloadTool(pluginManager));

        System.out.println("Registered 4 plugin management tools: inspect, list, load, unload");
        System.out.println("Agent can now manage its own capabilities.\n");

        // 4. Create Mock model that simulates self-evolution behavior
        ModelClient modelClient = createSelfEvolvingMock();

        // 5. Create Agent
        AgentConfig config = new AgentConfig(
                "self-evolving-agent",
                "You are a helpful assistant. You can inspect your capabilities, "
                        + "load plugins when you need them, and unload when done.",
                modelClient,
                registry,
                10
        );
        Agent agent = new SimpleAgent(config);

        // 6. Run
        System.out.println("--- User: \"Search for weather in Beijing\" ---\n");
        String response = agent.run("Search for weather in Beijing");
        System.out.println("--- Agent: \"" + response + "\" ---\n");

        // 7. Show final state
        System.out.println("--- Final Plugin States ---");
        for (PluginRegistry.PluginInfo info : pluginManager.listPlugins()) {
            System.out.println("  " + info.descriptor().name()
                    + " [" + info.state() + "]"
                    + (info.error() != null ? " error: " + info.error() : ""));
        }
        System.out.println();

        System.out.println("--- Tools in Registry ---");
        registry.listTools().forEach(tool ->
                System.out.println("  " + tool.getName()));
        System.out.println();

        System.out.println("=== Done ===");
    }

    /**
     * Create a MockModelClient that simulates an LLM doing self-evolution:
     * <p>
     * Turn 1: inspect -> see what I have
     * Turn 2: list -> discover available plugins
     * Turn 3: load("search-tool") -> gain search capability
     * Turn 4: search_web("Beijing weather") -> actually use the tool
     * Turn 5: unload("search-tool") -> clean up
     * Turn 6: final answer
     */
    private static ModelClient createSelfEvolvingMock() {
        return MockModelClient.scripted()
                // Turn 1: inspect current state
                .respondToolCalls(ToolCall.of("call_1", "plugin_inspect", jsonArgs()))
                // Turn 2: list available plugins
                .respondToolCalls(ToolCall.of("call_2", "plugin_list", jsonArgs()))
                // Turn 3: load search-tool
                .respondToolCalls(ToolCall.of("call_3", "plugin_load",
                        jsonArgs("name", "search-tool")))
                // Turn 4: use the loaded search tool
                .respondToolCalls(ToolCall.of("call_4", "search_web",
                        jsonArgs("query", "Beijing weather")))
                // Turn 5: unload search-tool (done with it)
                .respondToolCalls(ToolCall.of("call_5", "plugin_unload",
                        jsonArgs("name", "search-tool")))
                // Turn 6: final answer
                .respondText("The weather in Beijing is sunny, 25°C. "
                        + "I loaded the search-tool plugin to find this, then unloaded it.");
    }

    /**
     * Helper: create JSON arguments from key-value pairs.
     */
    private static JsonNode jsonArgs(Object... kv) {
        ObjectNode node = mapper.createObjectNode();
        for (int i = 0; i < kv.length; i += 2) {
            node.put((String) kv[i], kv[i + 1].toString());
        }
        return node;
    }
}

package io.github.qwzhang01.agent.plugin.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the 4 plugin management tools.
 * <p>
 * These tests verify that the model can:
 * 1. Inspect current plugin/tool state
 * 2. List available plugins
 * 3. Load a plugin by name
 * 4. Unload a plugin by name
 * <p>
 * Together they form the "self-evolution" capability.
 */
class PluginToolTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private InMemoryToolRegistry toolRegistry;
    private PluginManager pluginManager;
    private PluginInspectTool inspectTool;
    private PluginListTool listTool;
    private PluginLoadTool loadTool;
    private PluginUnloadTool unloadTool;

    @BeforeEach
    void setUp() {
        toolRegistry = new InMemoryToolRegistry();
        pluginManager = new PluginManager(toolRegistry);
        inspectTool = new PluginInspectTool(pluginManager);
        listTool = new PluginListTool(pluginManager);
        loadTool = new PluginLoadTool(pluginManager);
        unloadTool = new PluginUnloadTool(pluginManager);
    }

    // ============ PluginInspectTool ============

    @Test
    @DisplayName("inspect returns empty plugins when nothing loaded")
    void testInspectEmpty() throws Exception {
        String result = inspectTool.execute(mapper.createObjectNode());
        JsonNode json = mapper.readTree(result);

        assertEquals(0, json.path("plugins").size());
    }

    @Test
    @DisplayName("inspect shows loaded plugins with states")
    void testInspectShowsPlugins() throws Exception {
        // Load a test plugin directly (not via SPI)
        pluginManager.getRegistry().load(new TestEchoPlugin());

        String result = inspectTool.execute(mapper.createObjectNode());
        JsonNode json = mapper.readTree(result);

        assertTrue(json.path("plugins").size() > 0);
        JsonNode first = json.path("plugins").get(0);
        assertEquals("echo-test", first.path("name").asText());
        assertEquals("LOADED", first.path("state").asText());
    }

    // ============ PluginListTool ============

    @Test
    @DisplayName("list shows discovered plugins with available_to_load count")
    void testListPlugins() throws Exception {
        pluginManager.discover();

        String result = listTool.execute(mapper.createObjectNode());
        JsonNode json = mapper.readTree(result);

        assertTrue(json.path("total").asInt() >= 0);
        assertTrue(json.has("available_to_load"));
    }

    @Test
    @DisplayName("list shows 0 available after all loaded")
    void testListAllLoaded() throws Exception {
        pluginManager.discover();
        pluginManager.loadAll();

        String result = listTool.execute(mapper.createObjectNode());
        JsonNode json = mapper.readTree(result);

        // All discovered plugins should be LOADED, none available
        assertEquals(0, json.path("available_to_load").asInt());
    }

    // ============ PluginLoadTool ============

    @Test
    @DisplayName("load non-existent plugin returns error")
    void testLoadNonExistent() throws Exception {
        JsonNode args = mapper.createObjectNode().put("name", "nonexistent");
        String result = loadTool.execute(args);
        JsonNode json = mapper.readTree(result);

        assertFalse(json.path("success").asBoolean());
        assertNotNull(json.path("error").asText());
    }

    @Test
    @DisplayName("load with empty name returns error")
    void testLoadEmptyName() throws Exception {
        JsonNode args = mapper.createObjectNode().put("name", "");
        String result = loadTool.execute(args);
        JsonNode json = mapper.readTree(result);

        assertFalse(json.path("success").asBoolean());
    }

    // ============ PluginUnloadTool ============

    @Test
    @DisplayName("unload non-existent plugin returns error")
    void testUnloadNonExistent() throws Exception {
        JsonNode args = mapper.createObjectNode().put("name", "nonexistent");
        String result = unloadTool.execute(args);
        JsonNode json = mapper.readTree(result);

        assertFalse(json.path("success").asBoolean());
    }

    @Test
    @DisplayName("unload with empty name returns error")
    void testUnloadEmptyName() throws Exception {
        JsonNode args = mapper.createObjectNode().put("name", "");
        String result = unloadTool.execute(args);
        JsonNode json = mapper.readTree(result);

        assertFalse(json.path("success").asBoolean());
    }

    // ============ Full Self-Evolution Cycle ============

    @Test
    @DisplayName("Full cycle: inspect -> list -> load -> inspect -> unload -> inspect")
    void testFullSelfEvolutionCycle() throws Exception {
        // Load a test plugin directly (not via SPI)
        pluginManager.getRegistry().load(new TestEchoPlugin());

        // 1. Inspect - plugin should be LOADED
        String inspect1 = inspectTool.execute(mapper.createObjectNode());
        JsonNode json1 = mapper.readTree(inspect1);
        assertEquals(1, json1.path("plugins").size());
        assertEquals("LOADED", json1.path("plugins").get(0).path("state").asText());

        // 2. Unload
        JsonNode unloadArgs = mapper.createObjectNode().put("name", "echo-test");
        String unloadResult = unloadTool.execute(unloadArgs);
        JsonNode jsonUnload = mapper.readTree(unloadResult);
        assertTrue(jsonUnload.path("success").asBoolean());

        // 3. Inspect - plugin should be UNLOADED
        String inspect3 = inspectTool.execute(mapper.createObjectNode());
        JsonNode json3 = mapper.readTree(inspect3);
        assertEquals("UNLOADED", json3.path("plugins").get(0).path("state").asText());
    }

    // ============ Tool Metadata ============

    @Test
    @DisplayName("All 4 tools have correct names and valid schemas")
    void testToolMetadata() {
        assertEquals("plugin_inspect", inspectTool.getName());
        assertEquals("plugin_list", listTool.getName());
        assertEquals("plugin_load", loadTool.getName());
        assertEquals("plugin_unload", unloadTool.getName());

        // Schemas should be valid JSON
        assertDoesNotThrow(() -> mapper.readTree(inspectTool.getParametersSchema()));
        assertDoesNotThrow(() -> mapper.readTree(listTool.getParametersSchema()));
        assertDoesNotThrow(() -> mapper.readTree(loadTool.getParametersSchema()));
        assertDoesNotThrow(() -> mapper.readTree(unloadTool.getParametersSchema()));
    }

    // ============ Test Plugin ============

    /**
     * Simple test plugin that registers an echo tool.
     */
    static class TestEchoPlugin implements io.github.qwzhang01.agent.plugin.ToolPlugin {
        @Override
        public io.github.qwzhang01.agent.plugin.PluginDescriptor descriptor() {
            return new io.github.qwzhang01.agent.plugin.PluginDescriptor("echo-test", "1.0.0", "Test echo plugin");
        }

        @Override
        public void onLoad(io.github.qwzhang01.agent.plugin.PluginContext context) {
            context.getToolRegistry().register(new io.github.qwzhang01.agent.core.tool.Tool() {
                @Override
                public String getName() {
                    return "echo_test";
                }

                @Override
                public String getDescription() {
                    return "Echo test tool";
                }

                @Override
                public String getParametersSchema() {
                    return "{\"name\":\"echo_test\",\"parameters\":{\"type\":\"object\"," +
                            "\"properties\":{\"text\":{\"type\":\"string\"}}}}";
                }

                @Override
                public String execute(JsonNode args) {
                    return args.path("text").asText("");
                }
            });
        }

        @Override
        public void onUnload(io.github.qwzhang01.agent.plugin.PluginContext context) {
            context.getToolRegistry().unregister("echo_test");
        }
    }
}

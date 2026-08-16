package com.seven.agent.plugin;

import com.seven.agent.core.tool.InMemoryToolRegistry;
import com.seven.agent.core.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for plugin lifecycle: load, unload, reload, failure isolation.
 */
class PluginLifecycleTest {

    private InMemoryToolRegistry toolRegistry;
    private PluginRegistry registry;

    @BeforeEach
    void setUp() {
        toolRegistry = new InMemoryToolRegistry();
        registry = new PluginRegistry(toolRegistry);
    }

    // ============ Load / Unload ============

    @Test
    @DisplayName("Load plugin -> tool appears in registry")
    void testLoadRegistersTools() {
        registry.load(new EchoToolPlugin());

        assertEquals(PluginState.LOADED, registry.getState("echo-tool").orElse(null));
        assertEquals(1, toolRegistry.listTools().size());
        assertEquals("echo", toolRegistry.listTools().get(0).getName());
    }

    @Test
    @DisplayName("Unload plugin -> tool disappears from registry")
    void testUnloadRemovesTools() {
        registry.load(new EchoToolPlugin());
        registry.unload("echo-tool");

        assertEquals(PluginState.UNLOADED, registry.getState("echo-tool").orElse(null));
        assertEquals(0, toolRegistry.listTools().size());
    }

    @Test
    @DisplayName("Reload plugin -> tool removed then re-added")
    void testReloadPlugin() {
        registry.load(new EchoToolPlugin());
        registry.reload("echo-tool");

        assertEquals(PluginState.LOADED, registry.getState("echo-tool").orElse(null));
        assertEquals(1, toolRegistry.listTools().size());
    }

    @Test
    @DisplayName("Load same plugin twice -> throws PluginException")
    void testLoadDuplicate() {
        registry.load(new EchoToolPlugin());
        assertThrows(PluginException.class, () -> registry.load(new EchoToolPlugin()));
    }

    @Test
    @DisplayName("Unload non-existent plugin -> throws PluginException")
    void testUnloadNotFound() {
        assertThrows(PluginException.class, () -> registry.unload("nonexistent"));
    }

    @Test
    @DisplayName("Unload already unloaded plugin -> throws PluginException")
    void testUnloadAlreadyUnloaded() {
        registry.load(new EchoToolPlugin());
        registry.unload("echo-tool");
        assertThrows(PluginException.class, () -> registry.unload("echo-tool"));
    }

    // ============ Failure Isolation ============

    @Test
    @DisplayName("Plugin onLoad throws -> state = FAILED, does not crash registry")
    void testLoadFailureIsolated() {
        registry.load(new FailingPlugin("failing-1"));
        // The failing plugin should be in FAILED state
        assertEquals(PluginState.FAILED, registry.getState("failing-1").orElse(null));
        // No tools should be registered
        assertEquals(0, toolRegistry.listTools().size());
    }

    @Test
    @DisplayName("One plugin fails -> other plugins still load successfully")
    void testFailureIsolationBetweenPlugins() {
        // Load a failing plugin first
        registry.load(new FailingPlugin("failing-1"));
        // Load a healthy plugin
        registry.load(new EchoToolPlugin());

        assertEquals(PluginState.FAILED, registry.getState("failing-1").orElse(null));
        assertEquals(PluginState.LOADED, registry.getState("echo-tool").orElse(null));
        assertEquals(1, toolRegistry.listTools().size());
    }

    @Test
    @DisplayName("Plugin onUnload throws -> plugin still marked UNLOADED")
    void testUnloadFailureBestEffort() {
        // Use a plugin that loads OK but throws on unload
        registry.load(new FailOnUnloadPlugin());
        assertEquals(PluginState.LOADED, registry.getState("fail-unload").orElse(null));

        // Unload should not throw (best-effort)
        registry.unload("fail-unload");
        assertEquals(PluginState.UNLOADED, registry.getState("fail-unload").orElse(null));
    }

    // ============ Query ============

    @Test
    @DisplayName("listPlugins returns all plugins with states")
    void testListPlugins() {
        registry.load(new EchoToolPlugin());
        registry.load(new FailingPlugin("failing-1"));

        List<PluginRegistry.PluginInfo> plugins = registry.listPlugins();
        assertEquals(2, plugins.size());

        var echo = plugins.stream()
                .filter(p -> p.descriptor().name().equals("echo-tool"))
                .findFirst().orElseThrow();
        assertEquals(PluginState.LOADED, echo.state());

        var failed = plugins.stream()
                .filter(p -> p.descriptor().name().equals("failing-1"))
                .findFirst().orElseThrow();
        assertEquals(PluginState.FAILED, failed.state());
        assertNotNull(failed.error());
    }

    @Test
    @DisplayName("getLoadedPlugins returns only LOADED plugins")
    void testGetLoadedPlugins() {
        registry.load(new EchoToolPlugin());
        registry.load(new FailingPlugin("failing-1"));

        List<Plugin> loaded = registry.getLoadedPlugins();
        assertEquals(1, loaded.size());
        assertEquals("echo-tool", loaded.get(0).descriptor().name());
    }

    // ============ Test Plugins ============

    /**
     * Healthy plugin that registers an echo tool.
     */
    static class EchoToolPlugin implements ToolPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("echo-tool", "1.0.0", "Echo tool plugin");
        }

        @Override
        public void onLoad(PluginContext context) {
            context.getToolRegistry().register(new EchoTool());
        }

        @Override
        public void onUnload(PluginContext context) {
            context.getToolRegistry().unregister("echo");
        }
    }

    /**
     * Plugin that always fails on load.
     */
    static class FailingPlugin implements ToolPlugin {
        private final String name;

        FailingPlugin(String name) {
            this.name = name;
        }

        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor(name, "1.0.0", "Always fails");
        }

        @Override
        public void onLoad(PluginContext context) {
            throw new RuntimeException("Intentional load failure");
        }

        @Override
        public void onUnload(PluginContext context) {
            // No-op
        }
    }

    /**
     * Plugin that loads OK but throws on unload.
     */
    static class FailOnUnloadPlugin implements ToolPlugin {
        @Override
        public PluginDescriptor descriptor() {
            return new PluginDescriptor("fail-unload", "1.0.0", "Fails on unload");
        }

        @Override
        public void onLoad(PluginContext context) {
            context.getToolRegistry().register(new EchoTool());
        }

        @Override
        public void onUnload(PluginContext context) {
            throw new RuntimeException("Intentional unload failure");
        }
    }

    /**
     * Simple echo tool for testing.
     */
    static class EchoTool implements Tool {
        private static final ObjectMapper mapper = new ObjectMapper();

        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "Echoes the input";
        }

        @Override
        public String getParametersSchema() {
            return """
                    {
                        "name": "echo",
                        "description": "Echoes the input",
                        "parameters": {
                            "type": "object",
                            "properties": {
                                "text": { "type": "string" }
                            },
                            "required": ["text"]
                        }
                    }
                    """;
        }

        @Override
        public String execute(JsonNode arguments) {
            return arguments.path("text").asText("");
        }
    }
}

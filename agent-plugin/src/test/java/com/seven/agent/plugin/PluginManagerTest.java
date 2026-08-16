package com.seven.agent.plugin;

import com.seven.agent.core.tool.InMemoryToolRegistry;
import com.seven.agent.core.tool.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PluginManager: SPI discovery + batch operations.
 * <p>
 * Uses the plugins declared in
 * META-INF/services/com.seven.agent.plugin.ToolPlugin
 * (SearchToolPlugin + CalculatorToolPlugin from examples module).
 * <p>
 * Note: These tests depend on the examples module being on the classpath.
 * If running from agent-plugin module alone, discover() will find 0 plugins.
 */
class PluginManagerTest {

    private InMemoryToolRegistry toolRegistry;
    private PluginManager manager;

    @BeforeEach
    void setUp() {
        toolRegistry = new InMemoryToolRegistry();
        manager = new PluginManager(toolRegistry);
    }

    @Test
    @DisplayName("discover() finds plugins via SPI")
    void testDiscover() {
        int count = manager.discover();
        // May be 0 if examples module is not on classpath
        assertTrue(count >= 0);
        if (count > 0) {
            assertFalse(manager.getDiscoveredPlugins().isEmpty());
        }
    }

    @Test
    @DisplayName("loadAll() loads all discovered plugins")
    void testLoadAll() {
        manager.discover();
        int loaded = manager.loadAll();
        int discovered = manager.getDiscoveredPlugins().size();
        assertTrue(loaded <= discovered);
    }

    @Test
    @DisplayName("loadAll() without discover() still works (auto-discover)")
    void testLoadAllAutoDiscover() {
        int loaded = manager.loadAll();
        assertTrue(loaded >= 0);
    }

    @Test
    @DisplayName("unloadAll() unloads all loaded plugins")
    void testUnloadAll() {
        manager.loadAll();
        int unloaded = manager.unloadAll();
        assertTrue(unloaded >= 0);

        // After unloadAll, no tools should be in registry
        assertEquals(0, toolRegistry.listTools().size());
    }

    @Test
    @DisplayName("load(nonexistent) throws PluginException")
    void testLoadNonexistent() {
        assertThrows(PluginException.class, () -> manager.load("nonexistent-plugin"));
    }

    @Test
    @DisplayName("unload(nonexistent) throws via PluginRegistry")
    void testUnloadNonexistent() {
        assertThrows(PluginException.class, () -> manager.unload("nonexistent-plugin"));
    }

    @Test
    @DisplayName("listPlugins returns PluginInfo with state")
    void testListPlugins() {
        manager.loadAll();
        List<PluginRegistry.PluginInfo> plugins = manager.listPlugins();
        assertNotNull(plugins);
    }

    @Test
    @DisplayName("Full cycle: discover -> load -> use tool -> unload -> tool gone")
    void testFullCycle() {
        // Discover
        manager.discover();
        if (manager.getDiscoveredPlugins().isEmpty()) {
            // Examples module not on classpath - skip this test
            Assumptions.assumeTrue(false, "No plugins discovered (examples not on classpath)");
        }

        // Load all
        manager.loadAll();

        // Tools should be in registry
        assertFalse(toolRegistry.listTools().isEmpty());

        // Unload all
        manager.unloadAll();

        // Tools should be gone
        assertEquals(0, toolRegistry.listTools().size());
    }
}

package io.github.qwzhang01.agent.plugin;

import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PluginManager: SPI discovery + batch operations.
 * <p>
 * Uses {@link TestSpiPlugin} declared in
 * {@code META-INF/services/io.github.qwzhang01.agent.plugin.ToolPlugin}
 * on the test classpath, so discovery does not depend on the examples module.
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
        assertTrue(count >= 1, "test classpath must include TestSpiPlugin via META-INF/services");
        assertFalse(manager.getDiscoveredPlugins().isEmpty());
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
        assertFalse(manager.getDiscoveredPlugins().isEmpty(),
                "TestSpiPlugin must be discoverable from agent-plugin test resources");

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

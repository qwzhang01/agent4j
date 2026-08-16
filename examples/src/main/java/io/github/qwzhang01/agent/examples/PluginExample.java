package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.plugin.PluginManager;
import io.github.qwzhang01.agent.plugin.PluginRegistry;

/**
 * Demonstrates the plugin system: SPI discovery, load, unload, reload.
 * <p>
 * Run this to see plugins loaded via ServiceLoader,
 * tools appearing/disappearing in the registry at runtime.
 */
public class PluginExample {

    public static void main(String[] args) {
        System.out.println("=== Plugin System Demo ===\n");

        // 1. Create tool registry
        InMemoryToolRegistry registry = new InMemoryToolRegistry();

        // 2. Create plugin manager
        PluginManager manager = new PluginManager(registry);

        // 3. Discover plugins via SPI
        System.out.println("--- Step 1: Discover ---");
        int found = manager.discover();
        System.out.println("Discovered " + found + " plugins\n");

        // 4. Load all plugins
        System.out.println("--- Step 2: Load All ---");
        int loaded = manager.loadAll();
        System.out.println("Loaded " + loaded + " plugins\n");

        // 5. Show registry - tools should be present
        System.out.println("--- Step 3: Tools After Load ---");
        System.out.println("Tools in registry: " + registry.listTools().stream()
                .map(Tool -> Tool.getName()).toList());
        System.out.println();

        // 6. List plugin states
        System.out.println("--- Step 4: Plugin States ---");
        for (PluginRegistry.PluginInfo info : manager.listPlugins()) {
            System.out.println("  " + info.descriptor().name()
                    + " v" + info.descriptor().version()
                    + " [" + info.state() + "]"
                    + (info.error() != null ? " error: " + info.error() : ""));
        }
        System.out.println();

        // 7. Unload one plugin
        System.out.println("--- Step 5: Unload 'search-tool' ---");
        manager.unload("search-tool");
        System.out.println("Tools in registry: " + registry.listTools().stream()
                .map(Tool -> Tool.getName()).toList());
        System.out.println();

        // 8. Reload it
        System.out.println("--- Step 6: Reload 'search-tool' ---");
        manager.load("search-tool");
        System.out.println("Tools in registry: " + registry.listTools().stream()
                .map(Tool -> Tool.getName()).toList());
        System.out.println();

        // 9. Unload all
        System.out.println("--- Step 7: Unload All ---");
        manager.unloadAll();
        System.out.println("Tools in registry: " + registry.listTools().stream()
                .map(Tool -> Tool.getName()).toList());
        System.out.println();

        System.out.println("=== Done ===");
    }
}

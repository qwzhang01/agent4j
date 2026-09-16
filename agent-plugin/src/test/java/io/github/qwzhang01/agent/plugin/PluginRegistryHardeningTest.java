package io.github.qwzhang01.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 6.4: registry hardening — concurrent load/unload, load-failure
 * rollback, manifest permission gate, namespace isolation, unload kills
 * old tools (verifiable, not assumed).
 */
class PluginRegistryHardeningTest {

    private static Tool tool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "d"; }
            @Override public String getParametersSchema() {
                return "{\"type\":\"object\",\"properties\":{}}";
            }
            @Override public String execute(JsonNode arguments) { return "x"; }
        };
    }

    private static Plugin plugin(String name, boolean failOnLoad,
                                 List<String> toolsToRegister) {
        return new Plugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor(name, "1.0.0", "test");
            }

            @Override public void onLoad(PluginContext context) {
                for (String toolName : toolsToRegister) {
                    context.getToolRegistry().register(tool(toolName));
                }
                if (failOnLoad) {
                    throw new RuntimeException("boom after registering");
                }
            }

            @Override public void onUnload(PluginContext context) {
                for (String toolName : toolsToRegister) {
                    context.getToolRegistry().unregister(toolName);
                }
            }
        };
    }

    @Test
    void loadFailure_rollsBackAlreadyRegisteredTools() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);

        registry.load(plugin("half-loaded", true, List.of("tool_a", "tool_b")));

        assertEquals(PluginState.FAILED, registry.getState("half-loaded").orElse(null));
        assertTrue(tools.listTools().isEmpty(),
                "tools registered before the failure must be rolled back");
        assertTrue(registry.toolsOf("half-loaded").isEmpty());
    }

    @Test
    void manifestWithoutToolsPermission_registrationDenied() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"no-tools\",\"tools\":false}");

        registry.load(plugin("no-tools", true, List.of("forbidden_tool")), manifest);

        assertEquals(PluginState.FAILED, registry.getState("no-tools").orElse(null));
        assertTrue(tools.listTools().isEmpty(),
                "the manifest gate must fire BEFORE the tool lands in the registry");
    }

    @Test
    void manifestWithToolsPermission_registrationSucceeds() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"has-tools\",\"tools\":true,\"model\":true}");

        registry.load(plugin("has-tools", false, List.of("allowed_tool")), manifest);

        assertEquals(PluginState.LOADED, registry.getState("has-tools").orElse(null));
        assertEquals(1, tools.listTools().size());
    }

    @Test
    void manifestNameMismatch_refusedLoudly() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"other-name\",\"tools\":true}");

        PluginException e = assertThrows(PluginException.class,
                () -> registry.load(plugin("real-name", false, List.of()), manifest));
        assertTrue(e.getMessage().contains("does not match"));
    }

    @Test
    void namespaceIsolation_pluginCannotUnregisterForeignTools() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        tools.register(tool("host_tool"));  // the host's own tool
        PluginRegistry registry = new PluginRegistry(tools);

        // A hostile plugin registers its own tool, then tries to remove the
        // host's tool through its context view.
        Plugin hostile = new Plugin() {
            @Override public PluginDescriptor descriptor() {
                return new PluginDescriptor("hostile", "1.0.0", "tries to hurt the host");
            }

            @Override public void onLoad(PluginContext context) {
                context.getToolRegistry().register(tool("hostile_tool"));
                context.getToolRegistry().unregister("host_tool");  // must be refused
            }

            @Override public void onUnload(PluginContext context) {
            }
        };
        registry.load(hostile);

        assertTrue(tools.getTool("host_tool").isPresent(),
                "namespace isolation must refuse foreign tool removal");
        assertTrue(tools.getTool("hostile_tool").isPresent());
        registry.unload("hostile");
        assertFalse(tools.getTool("hostile_tool").isPresent(),
                "unload still sweeps the plugin's OWN tools");
        assertTrue(tools.getTool("host_tool").isPresent(),
                "unload must never touch foreign tools");
    }

    @Test
    void unload_oldToolNotCallableAnymore() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);
        registry.load(plugin("gone", false, List.of("stale_tool")));

        assertTrue(tools.getTool("stale_tool").isPresent());
        registry.unload("gone");

        assertFalse(tools.getTool("stale_tool").isPresent(),
                "after unload the old tool must not be callable");
        assertTrue(registry.toolsOf("gone").isEmpty());
    }

    @Test
    void concurrentLoadAndUnload_noCorruption() throws Exception {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);

        int rounds = 24;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[rounds];
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < rounds; i++) {
            String name = "p" + i;
            boolean unloadFirst = i % 2 == 0;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    registry.load(plugin(name, false, List.of(name + "_tool")));
                    if (unloadFirst) {
                        registry.unload(name);
                    }
                } catch (Throwable t) {
                    failures.incrementAndGet();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }

        assertEquals(0, failures.get());
        // Half the plugins were loaded+unloaded, half stayed loaded.
        long loadedCount = registry.listPlugins().stream()
                .filter(p -> p.state() == PluginState.LOADED)
                .count();
        long unloadedCount = registry.listPlugins().stream()
                .filter(p -> p.state() == PluginState.UNLOADED)
                .count();
        assertEquals(rounds / 2, loadedCount);
        assertEquals(rounds / 2, unloadedCount);
        Set<String> remainingTools = tools.listTools().stream()
                .map(Tool::getName).collect(Collectors.toSet());
        assertEquals(rounds / 2, remainingTools.size(),
                "every unloaded plugin's tools must be gone, every loaded one's present");
    }

    @Test
    void reloadPreservesManifestGate() {
        InMemoryToolRegistry tools = new InMemoryToolRegistry();
        PluginRegistry registry = new PluginRegistry(tools);
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"reload-me\",\"tools\":true}");

        registry.load(plugin("reload-me", false, List.of("rl_tool")), manifest);
        assertEquals(PluginState.LOADED, registry.getState("reload-me").orElse(null));
        registry.reload("reload-me");

        assertEquals(PluginState.LOADED, registry.getState("reload-me").orElse(null));
        assertTrue(tools.getTool("rl_tool").isPresent(),
                "reload must re-register the tool under the same manifest");
    }
}

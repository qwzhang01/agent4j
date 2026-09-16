package io.github.qwzhang01.agent.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 6.4: external-jar loading — checksum gate, per-jar classloader
 * isolation (framework-first resolution), SPI name consistency, honest
 * failure modes. The tests build REAL jars on the fly from generated
 * source, so the classloader path is exercised end-to-end, not mocked.
 */
class PluginJarLoaderTest {

    /** Source of a plugin compiled into the test jar at build time. */
    private static final String PLUGIN_SOURCE = """
            package demo;

            import com.fasterxml.jackson.databind.JsonNode;
            import io.github.qwzhang01.agent.core.tool.Tool;
            import io.github.qwzhang01.agent.plugin.*;

            public class DemoPlugin implements ToolPlugin {
                @Override
                public PluginDescriptor descriptor() {
                    return new PluginDescriptor("demo-jar-plugin", "1.0.0", "jar fixture");
                }

                @Override
                public void onLoad(PluginContext context) {
                    context.getToolRegistry().register(new Tool() {
                        @Override public String getName() { return "demo_jar_tool"; }
                        @Override public String getDescription() { return "demo"; }
                        @Override public String getParametersSchema() {
                            return "{\\"type\\":\\"object\\",\\"properties\\":{}}";
                        }
                        @Override public String execute(JsonNode arguments) {
                            return "loaded-from-jar:" + DemoPlugin.class.getClassLoader();
                        }
                    });
                }

                @Override
                public void onUnload(PluginContext context) {
                    context.getToolRegistry().unregister("demo_jar_tool");
                }
            }
            """;

    private static final String SPI_REGISTRATION =
            "demo.DemoPlugin\n";

    @Test
    void jarWithGoodChecksum_loadsRegistersTools_classloaderIsolated() throws Exception {
        Path jar = buildPluginJar();
        String sha256 = sha256Of(jar);
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"demo-jar-plugin\",\"tools\":true,\"sha256\":\"" + sha256 + "\"}");

        PluginJarLoader.LoadedJar loaded = PluginJarLoader.load(jar, manifest);
        try {
            assertEquals(1, loaded.plugins().size());
            assertEquals("demo-jar-plugin", loaded.plugins().get(0).descriptor().name());

            InMemoryToolRegistry tools = new InMemoryToolRegistry();
            PluginRegistry registry = new PluginRegistry(tools);
            registry.load(loaded.plugins().get(0), manifest);

            Tool demoTool = tools.getTool("demo_jar_tool").orElseThrow();
            // Framework classes resolve through the plugin's classloader to
            // the SAME framework classes (framework-first, no shadowing).
            assertSame(Plugin.class.getClassLoader().loadClass(
                    "io.github.qwzhang01.agent.plugin.Plugin"),
                    demoTool.getClass().getClassLoader()
                            .loadClass("io.github.qwzhang01.agent.plugin.Plugin"));
            // Plugin classes are NOT visible to the framework classloader.
            assertThrows(ClassNotFoundException.class, () ->
                    PluginJarLoader.class.getClassLoader().loadClass("demo.DemoPlugin"));
        } finally {
            PluginJarLoader.unload(loaded);
        }
    }

    @Test
    void tamperedChecksum_refusedBeforeAnyClassLoads() throws Exception {
        Path jar = buildPluginJar();
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"demo-jar-plugin\",\"tools\":true,"
                        + "\"sha256\":\"deadbeef" + "0".repeat(56) + "\"}");

        PluginException e = assertThrows(PluginException.class,
                () -> PluginJarLoader.load(jar, manifest));
        assertTrue(e.getMessage().contains("sha256 mismatch"), e.getMessage());
    }

    @Test
    void wrongManifestName_refused() throws Exception {
        Path jar = buildPluginJar();
        PluginManifest manifest = PluginManifest.fromJson(
                "{\"name\":\"someone-else\",\"tools\":true,\"sha256\":\"" + sha256Of(jar) + "\"}");

        PluginException e = assertThrows(PluginException.class,
                () -> PluginJarLoader.load(jar, manifest));
        assertTrue(e.getMessage().contains("does not match"), e.getMessage());
    }

    @Test
    void manifestWithoutName_rejectedAtParse() {
        assertThrows(IllegalArgumentException.class,
                () -> PluginManifest.fromJson("{\"tools\":true}"));
        assertThrows(IllegalArgumentException.class,
                () -> PluginManifest.fromJson("not json"));
    }

    @Test
    void manifest_defaultsAreDeny() {
        PluginManifest manifest = PluginManifest.fromJson("{\"name\":\"x\"}");
        assertFalse(manifest.tools());
        assertFalse(manifest.model());
        assertFalse(manifest.memory());
        assertFalse(manifest.security());
        assertNull(manifest.sha256());
    }

    // ============ Helpers ============

    /** Compile the fixture source and pack a jar with its SPI registration. */
    private static Path buildPluginJar() throws IOException {
        Path work = Files.createTempDirectory("plugin-jar-test");
        Path src = work.resolve("demo/DemoPlugin.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, PLUGIN_SOURCE, StandardCharsets.UTF_8);

        Path classes = work.resolve("classes");
        Files.createDirectories(classes);

        String classpath = System.getProperty("java.class.path");
        Process compile = new ProcessBuilder(
                System.getProperty("java.home") + "/bin/javac",
                "-cp", classpath,
                "-d", classes.toString(),
                src.toString())
                .inheritIO()
                .start();
        try {
            compile.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("javac interrupted", e);
        }
        if (compile.exitValue() != 0) {
            throw new IOException("javac failed with exit " + compile.exitValue());
        }

        Path jar = work.resolve("demo-plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            try (var walk = Files.walk(classes.resolve("demo"))) {
                for (Path classFile : walk
                        .filter(p -> p.toString().endsWith(".class"))
                        .sorted()
                        .toList()) {
                    String entryName = classes.relativize(classFile)
                            .toString().replace(java.io.File.separatorChar, '/');
                    out.putNextEntry(new JarEntry(entryName));
                    Files.copy(classFile, out);
                    out.closeEntry();
                }
            }
            out.putNextEntry(new JarEntry(
                    "META-INF/services/io.github.qwzhang01.agent.plugin.ToolPlugin"));
            out.write(SPI_REGISTRATION.getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar;
    }

    private static String sha256Of(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}

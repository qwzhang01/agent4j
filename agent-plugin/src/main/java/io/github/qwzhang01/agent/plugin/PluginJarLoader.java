package io.github.qwzhang01.agent.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Stage 6.4: loads plugins from EXTERNAL jars with per-plugin classloader
 * isolation, checksum verification, and (mandatory) host-signed manifests.
 * <p>
 * Roadmap: "设计外部 JAR ClassLoader 和版本隔离" + "增加插件签名、Checksum 和
 * 来源校验". Isolation model (honest): each jar gets its own
 * {@code URLClassLoader} parented to THIS framework's classloader, so a
 * plugin's bundled Jackson/Guava version cannot shadow or corrupt the
 * framework's — classes resolve framework-first. What this is NOT:
 * a security sandbox. A loaded plugin runs with full JVM permissions; the
 * classloader boundary is dependency hygiene, not confinement. Real
 * confinement (module layer + permission set) is a later-stage gap, stated
 * in the roadmap and the class docs of {@link Plugin}.
 * <p>
 * Checksum: when the manifest carries {@code sha256}, the jar bytes are
 * digested and compared BEFORE the classloader is built — a tampered jar
 * never gets a single class loaded.
 */
public final class PluginJarLoader {

    private PluginJarLoader() {
    }

    /** Outcome of a jar load: the plugin plus its classloader, for later unloading. */
    public record LoadedJar(List<ToolPlugin> plugins, java.net.URLClassLoader classLoader) {
    }

    /**
     * Verify the jar's SHA-256 against the manifest (when the manifest
     * declares one). Returns normally on match / no-checksum-configured;
     * throws {@link PluginException} on mismatch.
     */
    public static void verifyChecksum(Path jar, PluginManifest manifest) {
        String expected = manifest.sha256();
        if (expected == null || expected.isBlank()) {
            return; // host chose to skip integrity checking for this artifact
        }
        String actual;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(jar));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            actual = hex.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new PluginException(manifest.name(), "cannot digest jar: " + e, e);
        }
        if (!MessageDigest.isEqual(
                expected.toLowerCase().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new PluginException(manifest.name(),
                    "sha256 mismatch: jar tampered or wrong artifact (expected " + expected + ")");
        }
    }

    /**
     * Load all {@link ToolPlugin} implementations from an external jar.
     * <p>
     * Steps: checksum gate → own classloader (framework-first resolution)
     * → {@code ServiceLoader} inside that classloader → SPI entries whose
     * manifest name mismatches the plugin's descriptor name are refused
     * (a jar cannot smuggle in a plugin under someone else's name).
     *
     * @param jar      the plugin jar on disk
     * @param manifest the HOST-provided manifest (never read from the jar)
     * @return the plugins plus their classloader (close it on unload)
     */
    public static LoadedJar load(Path jar, PluginManifest manifest) throws IOException {
        java.util.Objects.requireNonNull(jar, "jar path must not be null");
        java.util.Objects.requireNonNull(manifest, "manifest must not be null");

        verifyChecksum(jar, manifest);

        java.net.URLClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{jar.toUri().toURL()},
                PluginJarLoader.class.getClassLoader()) {
            /**
             * External-jar isolation: ServiceLoader enumerates providers
             * through this loader, and the JDK's ServiceLoader looks up
             * META-INF/services resources via getResources() — which by
             * default walks the PARENT chain too, so host-classpath SPI
             * entries (e.g. our own test fixture) would leak into the jar's
             * plugin list. Only the jar's own registration file counts.
             */
            @Override
            public java.util.Enumeration<java.net.URL> getResources(String name) throws IOException {
                return super.findResources(name);
            }
        };
        List<ToolPlugin> plugins = new ArrayList<>();
        try {
            ServiceLoader<ToolPlugin> spi = ServiceLoader.load(ToolPlugin.class, loader);
            for (ToolPlugin plugin : spi) {
                String declared = plugin.descriptor().name();
                if (!manifest.name().equals(declared)) {
                    throw new PluginException(declared,
                            "manifest name '" + manifest.name() + "' does not match jar's SPI"
                                    + " descriptor name '" + declared + "'");
                }
                plugins.add(plugin);
            }
        } catch (Throwable t) {
            try {
                loader.close();
            } catch (IOException suppressed) {
                t.addSuppressed(suppressed);
            }
            throw t;
        }
        if (plugins.isEmpty()) {
            try {
                loader.close();
            } catch (IOException ignored) {
            }
            throw new PluginException(manifest.name(),
                    "jar declares no " + ToolPlugin.class.getName() + " SPI entries");
        }
        return new LoadedJar(plugins, loader);
    }

    /** Close a jar's classloader after unload (releases jar file handles). */
    public static void unload(LoadedJar jar) {
        try {
            jar.classLoader().close();
        } catch (IOException e) {
            throw new PluginException("jar-unload", "cannot close plugin classloader: " + e, e);
        }
    }
}

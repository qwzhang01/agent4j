package io.github.qwzhang01.agent.sandbox.process;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ProcessSandbox uses a real javac/java subprocess. Skips if the running
 * JDK has no javac (pure JRE).
 */
class ProcessSandboxTest {

    @Test
    void simpleCodeRunsInIsolatedProcess() throws Exception {
        assumeTrue(hasJavac(), "javac not found next to java.home");

        Path base = Files.createDirectories(Path.of("target/sandbox-tests"));
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(15))
                .build();

        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.print("hello-process");
                    }
                }
                """;

        SandboxResult result = new ProcessSandbox(spec).execute("Generated", code);
        assertTrue(result.success(), "stderr=" + result.stderr() + " error=" + result.error());
        assertTrue(result.stdout().contains("hello-process"));
    }

    @Test
    void infiniteLoopTimesOut() throws Exception {
        assumeTrue(hasJavac(), "javac not found next to java.home");

        Path base = Files.createDirectories(Path.of("target/sandbox-tests"));
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(2))
                .build();

        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        while (true) {}
                    }
                }
                """;

        SandboxResult result = new ProcessSandbox(spec).execute("Generated", code);
        assertFalse(result.success());
        assertTrue(result.timedOut());
    }

    @Test
    void memoryLimitBytesBecomesXmxOnChildJvm() throws Exception {
        assumeTrue(hasJavac(), "javac not found next to java.home");

        long limitBytes = 32L * 1024 * 1024;
        Path base = Files.createDirectories(Path.of("target/sandbox-tests"));
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(15))
                .memoryLimitBytes(limitBytes)
                .build();

        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.print(Runtime.getRuntime().maxMemory());
                    }
                }
                """;

        SandboxResult result = new ProcessSandbox(spec).execute("Generated", code);
        assertTrue(result.success(), "stderr=" + result.stderr() + " error=" + result.error());
        long maxMemory = Long.parseLong(result.stdout().trim());
        assertTrue(maxMemory <= limitBytes,
                "child maxMemory " + maxMemory + " must not exceed -Xmx " + limitBytes);
        assertTrue(maxMemory > 8L * 1024 * 1024,
                "child maxMemory " + maxMemory + " looks like the JVM failed to apply -Xmx32m");
    }

    @Test
    void xmxFlagUsesMegabyteSuffixWhenExact() {
        assertEquals("-Xmx32m", ProcessSandbox.xmxFlag(32L * 1024 * 1024));
        assertEquals("-Xmx1000", ProcessSandbox.xmxFlag(1000));
    }

    private static boolean hasJavac() {
        Path home = Path.of(System.getProperty("java.home"));
        Path javac = home.resolve("bin").resolve("javac");
        if (Files.isExecutable(javac)) {
            return true;
        }
        Path sibling = home.getParent() != null ? home.getParent().resolve("bin").resolve("javac") : null;
        return sibling != null && Files.isExecutable(sibling);
    }
}

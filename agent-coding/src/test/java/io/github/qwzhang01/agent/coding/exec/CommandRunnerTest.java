package io.github.qwzhang01.agent.coding.exec;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 17 M17.3: gate 3 - the no-shell executor. Runs real subprocesses (POSIX
 * utilities) against a temp workspace: cwd anchoring, timeout kill, head/tail output
 * truncation, and the D5 contract reuse (SandboxSpec in, SandboxResult out).
 */
class CommandRunnerTest {

    @TempDir
    Path tempDir;

    private Path root;
    private CommandRunner runner;

    @BeforeEach
    void setUp() throws IOException {
        root = Files.createDirectory(tempDir.resolve("project"));
        runner = new CommandRunner(root);
    }

    @Test
    @DisplayName("a plain command runs and its stdout is captured")
    void runsPlainCommand() {
        SandboxResult result = runner.run(List.of("echo", "hello"));

        assertTrue(result.success());
        assertEquals(0, result.exitCode());
        assertEquals("hello\n", result.stdout());
    }

    @Test
    @DisplayName("non-zero exit code is an honest failure, not a crash")
    void nonZeroExit() {
        SandboxResult result = runner.run(List.of("ls", "/definitely-not-there-xyz"));

        assertFalse(result.success());
        assertTrue(result.exitCode() != 0);
        assertTrue(result.error().contains("exited with code"), result.error());
    }

    @Test
    @DisplayName("the process cwd is pinned to the workspace root (anchoring, D2 gate 3)")
    void cwdAnchored() throws IOException {
        SandboxResult result = runner.run(List.of("pwd"));

        // pwd prints the real path (@TempDir may live behind a symlink, e.g. /var -> /private/var)
        assertEquals(root.toRealPath().toString() + "\n", result.stdout());
    }

    @Test
    @DisplayName("spec.workingDirectory is deliberately ignored - anchoring is not a hint")
    void specWorkingDirectoryIgnored() throws IOException {
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory("/tmp")
                .timeout(Duration.ofSeconds(5))
                .build();

        SandboxResult result = runner.run(List.of("pwd"), spec);

        assertEquals(root.toRealPath().toString() + "\n", result.stdout());
    }

    @Test
    @DisplayName("timeout kills the process: timedOut=true, honest failure, no exception")
    void timeoutKills() {
        SandboxResult result = new CommandRunner(root, Duration.ofMillis(300), 64 * 1024)
                .run(List.of("sleep", "30"));

        assertFalse(result.success());
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
    }

    @Test
    @DisplayName("huge output is truncated head+tail with an honest byte-count marker")
    void outputTruncation() {
        // seq 1 1000 produces ~3.9KB: above the 2KB budget, below the 8KB hard cap,
        // so both the head and the tail halves are real
        SandboxResult result = new CommandRunner(root, Duration.ofSeconds(30), 2048)
                .run(List.of("seq", "1", "1000"));

        assertTrue(result.success());
        String stdout = result.stdout();
        assertTrue(stdout.contains("[TRUNCATED: showing 2048 of "), stdout.substring(0, 200));
        assertTrue(stdout.startsWith("1\n2\n"), "head half preserved: " + stdout.substring(0, 20));
        assertTrue(stdout.endsWith("999\n1000\n"), "tail half preserved: "
                + stdout.substring(stdout.length() - 40));
    }

    @Test
    @DisplayName("output beyond the hard cap degrades honestly: head kept, count true, tail capped")
    void beyondHardCap() {
        // ~588KB output, hard cap is 4x2048=8KB: the buffer stops at the cap,
        // the marker reports the true total
        SandboxResult result = new CommandRunner(root, Duration.ofSeconds(30), 2048)
                .run(List.of("seq", "1", "100000"));

        assertTrue(result.success());
        String stdout = result.stdout();
        assertTrue(stdout.contains(" of 588895 bytes]"), "true total reported: "
                + stdout.substring(stdout.indexOf("[TRUNCATED"), Math.min(stdout.length(), stdout.indexOf("[TRUNCATED") + 60)));
        assertTrue(stdout.startsWith("1\n2\n"), "head half preserved");
    }

    @Test
    @DisplayName("output within budget flows through untruncated")
    void outputWithinBudget() {
        SandboxResult result = new CommandRunner(root, Duration.ofSeconds(10), 64 * 1024)
                .run(List.of("seq", "1", "10"));

        assertEquals("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n", result.stdout());
        assertFalse(result.stdout().contains("[TRUNCATED"));
    }

    @Test
    @DisplayName("spec environment variables reach the process")
    void environmentPassed() {
        SandboxSpec spec = SandboxSpec.builder()
                .timeout(Duration.ofSeconds(5))
                .environment(java.util.Map.of("MY_PROBE_VAR", "probe-value"))
                .build();

        SandboxResult result = runner.run(List.of("printenv", "MY_PROBE_VAR"), spec);

        assertTrue(result.success());
        assertEquals("probe-value\n", result.stdout());
    }

    @Test
    @DisplayName("unknown binary is an honest start failure, not a crash")
    void unknownBinary() {
        SandboxResult result = runner.run(List.of("definitely-not-a-command-xyz"));

        assertFalse(result.success());
        assertTrue(result.error().contains("failed to start"), result.error());
    }

    @Test
    @DisplayName("malformed argv fails fast (the whitelist should have caught it)")
    void malformedArgv() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.run(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.run(List.of()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> runner.run(List.of("echo", " ")));
    }
}

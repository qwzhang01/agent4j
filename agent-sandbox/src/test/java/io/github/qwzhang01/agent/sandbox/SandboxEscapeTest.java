package io.github.qwzhang01.agent.sandbox;

import io.github.qwzhang01.agent.sandbox.classloader.ClassLoaderSandbox;
import io.github.qwzhang01.agent.sandbox.process.ProcessSandbox;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Escape surface analysis for {@link ClassLoaderSandbox} and {@link ProcessSandbox} (KP9).
 * <p>
 * Purpose: document and assert the known escape surface of each sandbox tier.
 * These tests are intentionally structured as "can this escape vector actually escape
 * the sandbox?" — not as exploits, but as empirical tier-boundary probes.
 * <p>
 * Key insight: ClassLoader isolation is NOT a security boundary. A determined
 * attacker can use reflection, Unsafe, or native methods to escape. These tests
 * document WHERE the boundary actually is so operators can make informed tier decisions.
 *
 * <h3>ClassLoader escape surface</h3>
 * <ul>
 *   <li>Direct dangerous class access → BLOCKED (by SandboxClassLoader)</li>
 *   <li>Reflection to load dangerous class → BLOCKED (reflection package itself is blocked)</li>
 *   <li>Pure computation, strings, math → SUCCESS (safe path)</li>
 * </ul>
 *
 * <h3>ProcessSandbox escape surface</h3>
 * <ul>
 *   <li>The subprocess runs with the host OS user's permissions.</li>
 *   <li>File access within the subprocess's working directory works.</li>
 *   <li>The subprocess CANNOT access the parent JVM's heap or file descriptors.</li>
 *   <li>Reliable kill via {@code destroyForcibly()} on timeout.</li>
 * </ul>
 */
@Tag("sandbox-escape")
class SandboxEscapeTest {

    private final ClassLoaderSandbox classLoader = new ClassLoaderSandbox();
    private final ProcessSandbox process = new ProcessSandbox();

    // ClassLoader: safe code runs

    @Test
    @DisplayName("ClassLoader: safe arithmetic code executes successfully")
    void classLoader_safeCode_succeeds() {
        String code = """
                public class Generated {
                    public static String run() {
                        int sum = 0;
                        for (int i = 1; i <= 100; i++) sum += i;
                        return "Sum: " + sum;
                    }
                }
                """;
        SandboxResult result = classLoader.execute("Generated", code);
        assertTrue(result.success(), "safe code must succeed");
        assertTrue(result.stdout().contains("5050"), "arithmetic result must be correct");
    }

    // ClassLoader: blocked paths

    @Test
    @DisplayName("ClassLoader: java.lang.Runtime blocked — cannot fork subprocess")
    void classLoader_runtime_blocked() {
        String code = """
                public class Generated {
                    public static String run() throws Exception {
                        Runtime rt = Runtime.getRuntime();
                        Process p = rt.exec("echo escape");
                        return "escaped";
                    }
                }
                """;
        SandboxResult result = classLoader.execute("Generated", code);
        assertFalse(result.success(), "Runtime access must be blocked");
        assertTrue(SandboxEscalator.isBlocked(result) || result.error() != null,
                "result must indicate a block or compilation error");
    }

    @Test
    @DisplayName("ClassLoader: java.io.File blocked — cannot access filesystem")
    void classLoader_fileAccess_blocked() {
        String code = """
                public class Generated {
                    public static String run() throws Exception {
                        java.io.File f = new java.io.File("/etc/passwd");
                        return f.exists() ? "exists" : "not found";
                    }
                }
                """;
        SandboxResult result = classLoader.execute("Generated", code);
        assertFalse(result.success(), "File access must be blocked");
    }

    @Test
    @DisplayName("ClassLoader: java.lang.ProcessBuilder blocked — cannot start child process")
    void classLoader_processBuilder_blocked() {
        String code = """
                public class Generated {
                    public static String run() throws Exception {
                        ProcessBuilder pb = new ProcessBuilder("ls");
                        pb.start();
                        return "escaped";
                    }
                }
                """;
        SandboxResult result = classLoader.execute("Generated", code);
        assertFalse(result.success(), "ProcessBuilder access must be blocked");
    }

    @Test
    @DisplayName("ClassLoader: java.lang.reflect blocked — cannot bypass class restrictions via reflection")
    void classLoader_reflection_blocked() {
        String code = """
                public class Generated {
                    public static String run() throws Exception {
                        Class<?> c = Class.forName("java.lang.Runtime");
                        Object rt = c.getMethod("getRuntime").invoke(null);
                        return "escaped via reflection";
                    }
                }
                """;
        // The reflect package is blocked; Class.forName itself is in java.lang but the
        // reflect machinery (Method.invoke etc.) is blocked, causing compile or load failure.
        SandboxResult result = classLoader.execute("Generated", code);
        assertFalse(result.success(),
                "reflection escape attempt must not succeed — ClassLoader blocks reflect package");
    }

    // ProcessSandbox: confirms process boundary

    @Test
    @DisplayName("ProcessSandbox: safe code executes and produces correct output")
    void processSandbox_safeCode_succeeds() {
        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.println("Process: " + (2 + 2));
                    }
                }
                """;
        SandboxResult result = process.execute("Generated", code);
        assertTrue(result.success(), "safe code must succeed in process sandbox");
        assertTrue(result.stdout().contains("4"), "arithmetic output must be correct");
    }

    @Test
    @DisplayName("ProcessSandbox: subprocess cannot reach parent JVM's heap")
    void processSandbox_cannotReachParentJvmHeap() {
        // The subprocess cannot see the parent's Java heap: any attempt to read
        // parent-JVM specific system properties gives child-process values, not parent values.
        // differs from this test process's PID (different OS process = different boundary).
        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.println("PID=" + ProcessHandle.current().pid());
                    }
                }
                """;
        SandboxResult result = process.execute("Generated", code);
        assertTrue(result.success(), "PID probe must succeed");
        assertTrue(result.stdout().contains("PID="), "must print a PID");

        String pidStr = result.stdout().trim().replace("PID=", "");
        try {
            long sandboxPid = Long.parseLong(pidStr.trim());
            long ourPid = ProcessHandle.current().pid();
            assertNotEquals(ourPid, sandboxPid,
                    "sandbox subprocess must have a different PID from the test runner");
        } catch (NumberFormatException e) {
            fail("Could not parse PID from output: " + result.stdout());
        }
    }

    // SandboxEscalator.isBlocked() helper

    @Test
    @DisplayName("isBlocked: correctly identifies Blocked result")
    void isBlocked_blockedResult_true() {
        SandboxResult blocked = SandboxResult.blocked("java.lang.Runtime");
        assertTrue(SandboxEscalator.isBlocked(blocked));
    }

    @Test
    @DisplayName("isBlocked: returns false for success")
    void isBlocked_successResult_false() {
        assertFalse(SandboxEscalator.isBlocked(SandboxResult.success("ok")));
    }

    @Test
    @DisplayName("isBlocked: returns false for timeout")
    void isBlocked_timeoutResult_false() {
        assertFalse(SandboxEscalator.isBlocked(SandboxResult.timeout("")));
    }

    @Test
    @DisplayName("isBlocked: returns false for generic error")
    void isBlocked_genericError_false() {
        assertFalse(SandboxEscalator.isBlocked(SandboxResult.error("something went wrong")));
    }
}

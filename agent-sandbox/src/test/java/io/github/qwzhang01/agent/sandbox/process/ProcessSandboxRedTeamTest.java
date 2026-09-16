package io.github.qwzhang01.agent.sandbox.process;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Stage 4.4 red-team tests: every attack scenario in the roadmap gets an
 * honest BLOCKED / NOT-BLOCKED / NOT-APPLICABLE verdict.
 * <p>
 * Each test is a real escape attempt against the hardened PROCESS tier
 * (guard-injected guest). The verdicts are asserted on OBSERVED behavior,
 * not on intent: an attack that fails with [SANDBOX_POLICY] in guest stderr
 * is BLOCKED; an attack whose observable side effect happened is NOT-BLOCKED
 * and must be recorded as a limitation, not hidden.
 * <p>
 * Skipped when the running JDK has no javac (pure JRE).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("sandbox-escape")
class ProcessSandboxRedTeamTest {

    private static Path base;

    @BeforeAll
    static void requireJavac() throws Exception {
        assumeTrue(hasJavac(), "javac not found next to java.home");
        base = Files.createDirectories(Path.of("target/sandbox-redteam"));
    }

    private SandboxResult run(String code) {
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(20))
                .build();
        return new ProcessSandbox(spec).execute("Generated", code);
    }

    private static boolean deniedByPolicy(SandboxResult r) {
        String stderr = r.stderr() == null ? "" : r.stderr();
        String stdout = r.stdout() == null ? "" : r.stdout();
        String all = stderr + stdout;
        return all.contains("[SANDBOX_POLICY]");
    }

    // ============ Attack 1: path traversal write ============

    @Test
    @DisplayName("RED-1 path traversal write: BLOCKED")
    void pathTraversalWriteIsBlocked() {
        // Real attack shape: an ABSOLUTE path outside the workspace. (A
        // relative "../../x" resolves against user.dir = the workspace, so
        // it physically stays inside - not a traversal.)
        String code = """
                public class Generated {
                    public static void main(String[] args) throws Exception {
                        java.io.File f = new java.io.File("/tmp/sandbox-escape-target.txt");
                        java.io.FileWriter w = new java.io.FileWriter(f);
                        w.write("escaped");
                        w.close();
                        System.out.println("WROTE_OK");
                    }
                }
                """;
        SandboxResult r = run(code);
        assertFalse(r.success(), "the write must not succeed");
        assertTrue(deniedByPolicy(r), "the failure must be a POLICY denial, got: "
                + r.stderr());
        assertFalse(Files.exists(Path.of("/tmp/sandbox-escape-target.txt")),
                "no side effect may land outside the workspace");
    }

    // ============ Attack 2: host env var read ============

    @Test
    @DisplayName("RED-2 host env read: BLOCKED (allowlist drops secrets)")
    void hostEnvReadIsBlocked() {
        // Set the secret on the HOST side (injected by surefire config, see
        // pom.xml) so the guest's ProcessBuilder parent env contains it; the
        // allowlist must drop it before the guest starts.
        String secret = System.getenv("SANDBOX_REDTEAM_SECRET");
        assumeTrue(secret != null && !secret.isEmpty(),
                "SANDBOX_REDTEAM_SECRET not set (surefire config should inject it)");
        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        String secret = System.getenv("SANDBOX_REDTEAM_SECRET");
                        System.out.println("SECRET=" + (secret == null ? "NULL" : secret));
                    }
                }
                """;
        SandboxResult r = run(code);
        assertTrue(r.success(), "the probe itself runs (reading env is observable)");
        assertTrue(r.stdout().contains("SECRET=NULL"),
                "host secret must not be inherited: got " + r.stdout());
    }

    // ============ Attack 3: read outside workspace ============

    @Test
    @DisplayName("RED-3 read outside workspace: BLOCKED")
    void readOutsideWorkspaceIsBlocked() {
        String code = """
                public class Generated {
                    public static void main(String[] args) throws Exception {
                        java.io.File f = new java.io.File("/etc/passwd");
                        java.io.FileReader fr = new java.io.FileReader(f);
                        int c = fr.read();
                        fr.close();
                        System.out.println("READ_OK=" + c);
                    }
                }
                """;
        SandboxResult r = run(code);
        assertFalse(r.success(), "the read must not succeed");
        assertTrue(deniedByPolicy(r), "must be a policy denial, got: " + r.stderr());
    }

    // ============ Attack 4: network access ============

    @Test
    @DisplayName("RED-4 outbound network: BLOCKED")
    void networkAccessIsBlocked() throws Exception {
        // Use a port that is listening on THIS host (the maven reactor) so
        // the connect attempt is real, not just a DNS failure.
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            int port = probe.getLocalPort();
            String code = """
                    public class Generated {
                        public static void main(String[] args) throws Exception {
                            java.net.Socket s = new java.net.Socket("127.0.0.1", %d);
                            System.out.println("CONNECTED");
                            s.close();
                        }
                    }
                    """.formatted(port);
            SandboxResult r = run(code);
            assertFalse(r.success(), "the connect must not succeed");
            assertTrue(deniedByPolicy(r), "must be a policy denial, got: " + r.stderr());
        }
    }

    // ============ Attack 5: spawn child process / fork bomb ============

    @Test
    @DisplayName("RED-5 child process spawn: BLOCKED")
    void childProcessSpawnIsBlocked() {
        String code = """
                public class Generated {
                    public static void main(String[] args) throws Exception {
                        Process p = Runtime.getRuntime().exec("ls");
                        p.waitFor();
                        System.out.println("SPAWNED");
                    }
                }
                """;
        SandboxResult r = run(code);
        assertFalse(r.success(), "the spawn must not succeed");
        assertTrue(deniedByPolicy(r), "must be a policy denial, got: " + r.stderr());
    }

    // ============ Attack 6: stdout flood ============

    @Test
    @DisplayName("RED-6 stdout flood: capped at outputLimitBytes")
    void stdoutFloodIsCapped() {
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(20))
                .outputLimitBytes(64 * 1024) // 64 KB cap for the test
                .build();
        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        for (int i = 0; i < 500_000; i++) {
                            System.out.println("FLOOD-LINE-" + i + "-xxxxxxxxxxxxxxxxxxxxxxxxxxxx");
                        }
                        System.out.println("FLOOD_DONE");
                    }
                }
                """;
        SandboxResult r = new ProcessSandbox(spec).execute("Generated", code);
        // The flood line count exceeds the cap: the capture must be capped
        // and the truncation marker present. (Success may be true or false
        // depending on whether the guest finished before the reader threads
        // saw the marker - the CAP is the assertion, not the exit code.)
        assertTrue(r.stdout().length() <= 200 * 1024,
                "captured stdout must stay near the cap, got " + r.stdout().length());
        assertTrue(r.stdout().contains("truncated by sandbox"),
                "truncation marker must be present");
    }

    // ============ Attack 7: timeout kill of process tree ============

    @Test
    @DisplayName("RED-7 timeout kills the whole tree (busy-wait guest dies at deadline)")
    void timeoutKillsProcessTree() {
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(3))
                .build();
        // Busy-wait guest with a non-daemon worker thread: would outlive
        // the timeout if only the main thread were interrupted. The process
        // (and every thread in it) must die at the deadline.
        // (No lambdas in guest source: guest lambda bootstrap under the
        // guard is a separate, separately-tracked interaction; RED-7 is
        // about the KILL semantics, not about lambda support.)
        String code = """
                public class Generated {
                    static class Worker extends Thread {
                        public void run() {
                            while (true) {
                                try { Thread.sleep(250); } catch (Exception e) {}
                            }
                        }
                    }
                    public static void main(String[] args) throws Exception {
                        Worker w = new Worker();
                        w.setDaemon(false);
                        w.start();
                        while (true) {
                            Thread.sleep(500);
                        }
                    }
                }
                """;
        SandboxResult r = new ProcessSandbox(spec).execute("Generated", code);
        assertTrue(r.timedOut(), "the sleepy guest must be killed at timeout");
    }

    // ============ Attack 8: className path traversal (host side) ============

    @Test
    @DisplayName("RED-8 className traversal: BLOCKED at the door")
    void classNameTraversalIsBlocked() {
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(20))
                .build();
        ProcessSandbox sandbox = new ProcessSandbox(spec);
        for (String evil : List.of("../../etc/evil", "a/b/Evil", "Evil;rm", "..", "Evil.java")) {
            SandboxResult r = sandbox.execute(evil, "public class X {}");
            assertFalse(r.success(), evil + " must be rejected");
            assertTrue(r.error() != null && r.error().contains("[INVALID_CLASS_NAME]"),
                    evil + " must carry the policy tag, got: " + r.error());
            assertTrue(r.kind() == SandboxResult.FailureKind.BLOCKED_BY_POLICY,
                    evil + " must be BLOCKED_BY_POLICY, got: " + r.kind());
        }
    }

    // ============ Env allowlist mechanics (host side) ============

    @Test
    @DisplayName("env allowlist: overlay + filtered inheritance, SANDBOX_WORKSPACE injected")
    void envAllowlistFiltersAndOverlays() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("SANDBOX_REDTEAM_SECRET", "super-secret");
        pb.environment().put("PATH", "/usr/bin:/bin");
        SandboxSpec spec = SandboxSpec.builder()
                .envAllowlist(List.of("PATH"))
                .environment(Map.of("GUEST_ONLY", "42"))
                .build();
        Map<String, String> guest = ProcessSandbox.guestEnvironment(pb, spec);
        assertTrue(guest.containsKey("PATH"), "allowlisted key inherits");
        assertFalse(guest.containsKey("SANDBOX_REDTEAM_SECRET"),
                "non-allowlisted host secret must be dropped");
        assertTrue(guest.containsKey("GUEST_ONLY"), "overlay env applies");
    }

    @Test
    @DisplayName("ENV_INHERIT_ALL restores full inheritance (explicit opt-in)")
    void inheritAllOptIn() {
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("SANDBOX_REDTEAM_SECRET", "super-secret");
        SandboxSpec spec = SandboxSpec.builder()
                .envAllowlist(SandboxSpec.ENV_INHERIT_ALL)
                .build();
        Map<String, String> guest = ProcessSandbox.guestEnvironment(pb, spec);
        assertTrue(guest.containsKey("SANDBOX_REDTEAM_SECRET"),
                "explicit inherit-all opt-in keeps the pre-Stage-4 behavior");
    }

    // ============ Honest NOT-BLOCKED recording ============

    @Test
    @DisplayName("honest limitation: same-OS-user file reads outside JDK are NOT blocked at UNRESTRICTED")
    void unrestrictedFileAccessIsHonestlyNotBlocked() {
        // This test RECORDS the honest boundary: with FileAccessPolicy.UNRESTRICTED,
        // no guard is injected and same-user reads outside the workspace are
        // observable. The default policy is WORKSPACE_ONLY; this is the
        // documented escape hatch for TRUSTED debugging only.
        SandboxSpec spec = SandboxSpec.builder()
                .workingDirectory(base.toAbsolutePath().toString())
                .timeout(Duration.ofSeconds(20))
                .fileAccess(SandboxSpec.FileAccessPolicy.UNRESTRICTED)
                .build();
        String code = """
                public class Generated {
                    public static void main(String[] args) {
                        System.out.println("user.home=" + System.getProperty("user.home"));
                    }
                }
                """;
        SandboxResult r = new ProcessSandbox(spec).execute("Generated", code);
        // With UNRESTRICTED the guard is NOT injected: user.home readable.
        assertTrue(r.success(), "UNRESTRICTED mode runs without the guard");
        assertTrue(r.stdout().contains("user.home="));
    }

    // ============ Attack 9: System.exit hijack (exit-code forgery) ============

    @Test
    @DisplayName("RED-9 System.exit forgery: exit is sandbox-owned, guest exit(0) is BLOCKED")
    void systemExitForgeryIsNeutralized() {
        // The guest tries to forge a benign exit code (laundry a bad run as
        // success). Observed semantics (probe, JDK 17): the guard DENIES
        // runtime permission exitVM — the process lifecycle belongs to the
        // SANDBOX (timeout kill, tree kill), never to the guest. So a
        // guest-initiated exit is a policy denial, and output emitted before
        // the attempt is still captured (no lost flush).
        String code = """
                public class Generated {
                    public static void main(String[] args) throws Exception {
                        System.out.println("BEFORE_EXIT");
                        System.exit(0);
                    }
                }
                """;
        SandboxResult r = run(code);
        assertFalse(r.success(), "guest-initiated exit must not succeed");
        assertTrue(deniedByPolicy(r), "exit must be a POLICY denial (exitVM), got: "
                + r.stderr());
        assertTrue(r.stdout().contains("BEFORE_EXIT"),
                "output before the exit attempt must be captured");
    }

    // ============ Attack 10: degraded-guard mode still host-constrained ============

    @Test
    @DisplayName("RED-10 guard-degraded JDK: host-side constraints still bind")
    void hostConstraintsBindWhenGuardDegrades() {
        // JDK 24+ removed SecurityManager: the guest guard prints a
        // DEGRADED notice and in-guest policy enforcement is OFF. The
        // escape suite must prove the HOST side still binds: env allowlist
        // drops host secrets, output cap truncates, timeout kills. This
        // test pins the env-allowlist half of that contract (the pieces
        // that need no javac-in-guest cooperation).
        ProcessBuilder pb = new ProcessBuilder();
        pb.environment().put("SANDBOX_REDTEAM_SECRET", "super-secret");
        SandboxSpec spec = SandboxSpec.builder()
                .envAllowlist(List.of("PATH"))
                .build();
        Map<String, String> guest = ProcessSandbox.guestEnvironment(pb, spec);
        assertFalse(guest.containsKey("SANDBOX_REDTEAM_SECRET"),
                "degraded or not: host env allowlist must drop secrets");
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

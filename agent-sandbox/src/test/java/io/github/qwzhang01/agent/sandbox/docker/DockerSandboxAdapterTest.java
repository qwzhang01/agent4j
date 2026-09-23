package io.github.qwzhang01.agent.sandbox.docker;

import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import io.github.qwzhang01.agent.sandbox.SandboxTier;
import io.github.qwzhang01.agent.sandbox.SandboxReport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness batch 6: {@link DockerSandboxAdapter} skeleton contract. The
 * command assembly is a pure function (fully testable on any machine,
 * daemon or not); the execution path is loud-fail (the placeholder
 * contract from {@code DockerDaemonProbeIT} extended to the adapter
 * itself). Daemon-dependent lifecycle integration waits for the Linux
 * CI profile — deliberately NOT tagged as an IT here: a skeleton that
 * pretends to have a wire test would be coverage theater.
 */
class DockerSandboxAdapterTest {

    private static final String CMD_JAVA = "java";
    private static final String WORKSPACE = "/workspace";

    // Command assembly (pure)

    @Test
    void commandCarriesEveryHardeningRow() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        List<String> cmd = adapter.dockerRunCommand(
                SandboxSpec.builder().build(), List.of(CMD_JAVA, "Guest"), WORKSPACE);

        assertTrue(cmd.contains("--rm"), "container must be removed on exit (cleanup row)");
        int uidIdx = cmd.indexOf("--user");
        assertTrue(uidIdx > 0 && "65534:65534".equals(cmd.get(uidIdx + 1)),
                "unprivileged uid/gid row: --user 65534:65534, got: " + cmd);
        int capIdx = cmd.indexOf("--cap-drop");
        assertTrue(capIdx > 0 && "ALL".equals(cmd.get(capIdx + 1)),
                "capability drop row: --cap-drop ALL");
        int seccompIdx = cmd.indexOf("--security-opt");
        assertTrue(seccompIdx > 0 && "seccomp=default".equals(cmd.get(seccompIdx + 1)),
                "seccomp row: default profile, never unconfined");
        assertTrue(cmd.contains("--read-only"), "read-only rootfs row");
        int tmpfsIdx = cmd.indexOf("--tmpfs");
        assertTrue(tmpfsIdx > 0 && cmd.get(tmpfsIdx + 1).startsWith(WORKSPACE),
                "writable tmpfs workspace mount row");
        int netIdx = cmd.indexOf("--network");
        assertTrue(netIdx > 0 && "none".equals(cmd.get(netIdx + 1)),
                "network deny-all row (networkBlocked defaults true)");
        int memIdx = cmd.indexOf("--memory");
        assertTrue(memIdx > 0 && cmd.get(memIdx + 1).endsWith("b"),
                "cgroup memory ceiling from spec (default 256MB)");
        int cpusIdx = cmd.indexOf("--cpus");
        assertTrue(cpusIdx > 0 && "1.0".equals(cmd.get(cpusIdx + 1)),
                "cgroup CPU ceiling row");
    }

    @Test
    void memoryCeilingFollowsTheSpec() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        List<String> cmd = adapter.dockerRunCommand(
                SandboxSpec.builder().memoryLimitBytes(512 * 1024 * 1024).build(),
                List.of(CMD_JAVA), WORKSPACE);
        int memIdx = cmd.indexOf("--memory");
        assertEquals(512 * 1024 * 1024 + "b", cmd.get(memIdx + 1));
    }

    @Test
    void zeroMemoryLimitOmitsTheCeiling() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        List<String> cmd = adapter.dockerRunCommand(
                SandboxSpec.builder().memoryLimitBytes(0).build(),
                List.of(CMD_JAVA), WORKSPACE);
        assertFalse(cmd.contains("--memory"),
                "spec.memoryLimitBytes=0 means no cgroup memory flag (unbounded is the "
                        + "caller's explicit choice, same convention as ProcessSandbox -Xmx)");
    }

    @Test
    void networkAllowedOmitsNone() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        List<String> cmd = adapter.dockerRunCommand(
                SandboxSpec.builder().networkBlocked(false).build(),
                List.of(CMD_JAVA), WORKSPACE);
        assertFalse(cmd.contains("--network"),
                "spec.networkBlocked=false: no --network none (allowlist form waits for "
                        + "CI; absent flag = Docker default bridge, an honest gap, not a "
                        + "fabricated allowlist)");
    }

    @Test
    void imageReferenceDefaultsAndDigestPinning() {
        DockerSandboxAdapter plain = new DockerSandboxAdapter(null);
        assertEquals(DockerSandboxAdapter.DEFAULT_IMAGE, plain.imageReference());
        assertFalse(plain.isDigestPinned(), "a tag reference is honestly not digest-pinned");

        DockerSandboxAdapter pinned = new DockerSandboxAdapter(
                "registry.example/agent4j/jdk@sha256:abcdef0123456789");
        assertTrue(pinned.isDigestPinned(), "repo@sha256:... references are digest-pinned");
        assertTrue(pinned.dockerRunCommand(
                        SandboxSpec.builder().build(), List.of(CMD_JAVA), WORKSPACE)
                .contains("registry.example/agent4j/jdk@sha256:abcdef0123456789"),
                "the pinned reference rides the command verbatim");
    }

    @Test
    void blankImageFallsBackToDefault() {
        DockerSandboxAdapter blank = new DockerSandboxAdapter("  ");
        assertEquals(DockerSandboxAdapter.DEFAULT_IMAGE, blank.imageReference());
    }

    // Execution path (loud-fail stub)

    @Test
    void invalidClassNameIsRefusedBeforeAnyDaemonProbe() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        SandboxResult result = adapter.execute("../../../etc/passwd", "class X {}");
        assertFalse(result.success());
        assertTrue(result.error().startsWith("[INVALID_CLASS_NAME]"),
                "the Stage 4.1 className gate runs before any docker invocation");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, result.kind());

        SandboxResult nullName = adapter.execute(null, "class X {}");
        assertTrue(nullName.error().startsWith("[INVALID_CLASS_NAME]"));
    }

    @Test
    void missingDaemonRefusesLoudlyNoFallback() {
        DockerSandboxAdapter adapter = new DockerSandboxAdapter(null);
        // The host may or may not have a daemon; both paths refuse
        // loudly with a named reason. We cannot assert which branch a
        // random host takes, but we CAN assert the refusal contract:
        // never success, never a weaker tier's output shape.
        SandboxResult result = adapter.execute("Guest", "class Guest {}");
        assertFalse(result.success(), "the skeleton never half-executes");
        assertNotNull(result.error());
        assertTrue(result.error().startsWith("[DOCKER_DAEMON_UNAVAILABLE]")
                        || result.error().startsWith("[DOCKER_TIER_NOT_INTEGRATED]"),
                "refusal must name the exact reason, got: " + result.error());
        assertFalse(result.error().contains("classpath"),
                "never a CLASSLOADER/PROCESS-shaped fallback message");
    }

    // Report integration (placeholder stays honest)

    @Test
    void dockerTierReportStillClaimsZeroGuarantees() {
        // The adapter exists now, but the tier's SandboxReport entry
        // stays the honest placeholder until the lifecycle integration
        // lands: guarantees are claimed by INTEGRATED tiers only.
        SandboxReport.Entry entry = SandboxReport.report(SandboxTier.DOCKER,
                SandboxResult.success("ok"));
        assertTrue(entry.guarantees().isEmpty(),
                "guarantees land with the Linux CI integration batch, not the skeleton");
        assertTrue(entry.notGuaranteed().stream().anyMatch(s -> s.contains("placeholder")));
    }

    @Test
    void hardeningSurfaceDocumentsEveryMechanism() {
        // The designed-but-not-integrated mechanisms are data, not prose
        // buried in javadoc: the surface map is what a future report
        var surface = DockerSandboxAdapter.hardeningSurface();
        assertEquals(7, surface.size());
        for (String key : List.of("uidGid", "capabilities", "seccomp", "rootfs",
                "network", "cgroups", "image")) {
            assertTrue(surface.containsKey(key), "missing 4.3 mechanism row: " + key);
        }
    }
}

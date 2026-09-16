package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8.3 container Sandbox Integration Profile.
 *
 * <p>v1 scope decision (see {@link SandboxTier#DOCKER}): the DOCKER tier is a
 * documented placeholder -- no adapter implementation exists, and a
 * SandboxReport for it can claim zero guarantees. This profile pins the
 * integration story around that decision instead of pretending containers
 * are supported:</p>
 *
 * <ul>
 *   <li>docker daemon presence is probed and recorded (skippable, auditable),
 *       mirroring the python3 probe pattern of McpStdioIT;</li>
 *   <li>when a daemon IS present, the placeholder contract must still hold
 *       (loud-fail: report says "no implementation", never a silent
 *       fallback to a weaker tier);</li>
 *   <li>when no daemon exists (the default CI/dev box), the loud-fail
 *       contract is pinned on the report layer alone.</li>
 * </ul>
 *
 * <p>Tagged {@code sandbox-escape} alongside the red-team suite: the
 * container profile is the tier the escape regressions are escalating
 * toward, and CI runs them together.</p>
 */
@Tag("sandbox-escape")
class DockerDaemonProbeIT {

    private static final String TAG = "sandbox-escape";

    /** Cached daemon probe: null = not probed yet. */
    private static Boolean daemonAvailable;

    private Path workdir;

    @BeforeEach
    void requireDockerProbe() throws IOException {
        workdir = Files.createTempDirectory("docker-probe-it");
    }

    @AfterEach
    void cleanup() {
        if (workdir != null) {
            try {
                Files.walk(workdir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // best effort; temp dir cleanup is not the
                                // assertion under test
                            }
                        });
            } catch (IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * Probe the docker daemon once per JVM. Uses {@code docker info} with a
     * short timeout: a healthy daemon answers in well under 10s; a missing
     * binary or a stopped daemon fails fast (exit != 0 or IOException).
     * The result is cached so repeated tests do not pay the probe cost.
     */
    private static boolean dockerDaemonAvailable() {
        Boolean cached = daemonAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            Process p = new ProcessBuilder(List.of("docker", "info", "--format", "{{.ServerVersion}}"))
                    .redirectErrorStream(true)
                    .start();
            available = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException dockerBinaryMissing) {
            available = false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            available = false;
        }
        daemonAvailable = available;
        return available;
    }

    @Test
    void dockerProbe_resultIsRecorded_notAssumed() {
        // Whether or not a daemon exists, the probe itself must answer
        // deterministically -- this is the auditable skip contract: we never
        // silently assume docker away, we probe and record.
        boolean available = dockerDaemonAvailable();
        // No assertion on `available` itself: its value depends on the host.
        // The assertion is that the cache is populated and consistent.
        assertEquals(Boolean.valueOf(available), daemonAvailable,
                "probe result must be cached after the first call");
        assertSame(Boolean.valueOf(dockerDaemonAvailable()), daemonAvailable,
                "second call must return the cached probe result");
    }

    @Test
    void dockerTier_reportClaimsZeroGuarantees_loudFail() {
        // The placeholder contract, pinned regardless of daemon presence:
        // a DOCKER-tier report can NEVER claim guarantees or silently fall
        // back to a weaker tier's semantics. This is the loud-fail
        // guarantee SandboxReportAndBudgetTest pins for all placeholder
        // tiers; here it is asserted in the container profile context so a
        // future DOCKER adapter that forgets the contract fails this suite
        // first.
        SandboxResult result = SandboxResult.success("ok");
        SandboxReport.Entry entry = SandboxReport.report(SandboxTier.DOCKER, result);

        assertTrue(entry.guarantees().isEmpty(),
                "DOCKER placeholder must claim ZERO guarantees");
        assertTrue(entry.notGuaranteed().stream()
                        .anyMatch(s -> s.contains("placeholder")),
                "DOCKER report must loudly say it is a placeholder, got: "
                        + entry.notGuaranteed());
        assertNotNull(entry.escalationNote(),
                "DOCKER report must carry an escalation note");
    }

    @Test
    void dockerDaemonPresent_placeholderStillLoudFails() {
        // When a daemon IS available (a dev box with Docker Desktop), the
        // v1 contract is unchanged: the library still has no DOCKER adapter,
        // so the report must still claim zero guarantees. Presence of the
        // daemon is the future integration hook, not a current feature flag.
        org.junit.jupiter.api.Assumptions.assumeTrue(dockerDaemonAvailable(),
                "docker daemon not present on this host - probing contract "
                        + "still covered by dockerProbe_resultIsRecorded_notAssumed");

        SandboxResult result = SandboxResult.success("ok");
        SandboxReport.Entry entry = SandboxReport.report(SandboxTier.DOCKER, result);

        assertTrue(entry.guarantees().isEmpty(),
                "even with a live daemon, the DOCKER placeholder must claim "
                        + "zero guarantees (no adapter exists in v1)");
    }
}

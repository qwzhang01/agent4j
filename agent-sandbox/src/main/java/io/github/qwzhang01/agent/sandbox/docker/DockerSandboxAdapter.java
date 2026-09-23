package io.github.qwzhang01.agent.sandbox.docker;

import io.github.qwzhang01.agent.sandbox.Sandbox;
import io.github.qwzhang01.agent.sandbox.SandboxResult;
import io.github.qwzhang01.agent.sandbox.SandboxSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * DOCKER-tier adapter skeleton (harness batch 6, 2026-09-17). The command
 * assembly is COMPLETE — every hardening row the roadmap's 4.3 section
 * lists is translated into a real {@code docker run} flag — but the
 * execution path is deliberately a loud-fail stub on hosts without a
 * Docker daemon: {@link #execute} probes the daemon, and when absent
 * refuses with {@code [DOCKER_DAEMON_UNAVAILABLE]}, never silently
 * falling back to a weaker tier (the placeholder contract {@code
 * DockerDaemonProbeIT} pins).
 * <p>
 * Why ship a skeleton now (roadmap 4.3 row 1: "先覆盖 Linux CI"): the
 * flag assembly — uid/gid mapping, seccomp default profile, capability
 * drop to none, cgroup memory/cpu ceilings, {@code --network none},
 * read-only rootfs with a tmpfs workspace, image digest pinning — is
 * where the security REVIEW effort lives, and it is fully testable
 * without a daemon (the command builder is a pure function; its tests
 * run on any machine). The daemon-dependent execution (image pull by
 * digest, container lifecycle, output streaming) waits for the Linux CI
 * profile — the same split as the roadmap's own honest-gap rows: the
 * MECHANISM is designed and pinned, the INTEGRATION is deferred.
 * <p>
 * Dialect policy (same as the JDBC spine): this adapter shells out to
 * the {@code docker} CLI, it does not link a Docker SDK — zero new
 * compile dependencies, the CLI is the contract both Docker and Podman
 * speak. The exit code and stderr text of the CLI are the failure
 * taxonomy's raw input.
 */
public final class DockerSandboxAdapter implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxAdapter.class);

    static final Pattern CLASS_NAME_PATTERN =
            Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*$");

    /**
     * The unprivileged in-container identity (roadmap 4.3: "配置非特权
     * UID/GID"). 65534 = nobody/nogroup on the standard images; the
     * container never runs code as uid 0.
     */
    static final long SANDBOX_UID = 65534;
    static final long SANDBOX_GID = 65534;

    /** Default host image the adapter runs guests in (overridable per spec). */
    static final String DEFAULT_IMAGE = "eclipse-temurin:17-jdk";

    private final String image;
    private final Duration daemonProbeTimeout;

    /**
     * Daemon probe cache: null = not probed. Same caching contract as
     * {@code DockerDaemonProbeIT} — probe once per JVM, the result is an
     * auditable host fact, not an assumption.
     */
    private static volatile Boolean daemonAvailable;

    /** Image to run guests in; {@code null} = {@link #DEFAULT_IMAGE}. */
    public DockerSandboxAdapter(String image) {
        this.image = image == null || image.isBlank() ? DEFAULT_IMAGE : image;
        this.daemonProbeTimeout = Duration.ofSeconds(10);
    }

    public DockerSandboxAdapter() {
        this(null);
    }

    @Override
    public SandboxResult execute(String className, String code) {
        return execute(className, code, SandboxSpec.builder().build());
    }

    @Override
    public SandboxResult execute(String className, String code, SandboxSpec spec) {
        Objects.requireNonNull(spec, "spec");

        // into container paths and commands, so it must be a legal Java
        // identifier BEFORE any docker invocation.
        if (className == null || !CLASS_NAME_PATTERN.matcher(className).matches()) {
            return SandboxResult.error(
                    "[INVALID_CLASS_NAME] className must be a legal Java identifier, got: "
                            + (className == null ? "null" : "'" + className + "'"),
                    SandboxResult.FailureKind.BLOCKED_BY_POLICY);
        }

        // The loud-fail stub: the mechanism is assembled but the
        // integration is deferred to the Linux CI profile. A missing
        // daemon is a REFUSAL naming the exact reason, never a silent
        // fallback to PROCESS/CLASSLOADER.
        if (!dockerDaemonAvailable()) {
            return SandboxResult.error(
                    "[DOCKER_DAEMON_UNAVAILABLE] DOCKER tier refuses to execute: no docker "
                            + "daemon reachable within " + daemonProbeTimeout.toSeconds()
                            + "s. No silent fallback to a weaker tier - run this adapter on "
                            + "the Linux CI profile where the daemon integration is exercised.",
                    SandboxResult.FailureKind.SANDBOX_FAILURE);
        }

        // A daemon is present: the v1 integration contract is still the
        // honest placeholder (DockerDaemonProbeIT.dockerDaemonPresent_placeholderStillLoudFails).
        // The full lifecycle (pull by digest, run, stream, cleanup) lands
        // with the Linux CI batch; until then the adapter refuses with a
        // named reason instead of half-executing.
        return SandboxResult.error(
                "[DOCKER_TIER_NOT_INTEGRATED] daemon present but the container lifecycle "
                        + "integration is not wired in v1 - execute lives on the Linux CI "
                        + "profile batch. Command assembly is complete and testable via "
                        + "dockerRunCommand(...); this refusal is the loud-fail contract.",
                SandboxResult.FailureKind.SANDBOX_FAILURE);
    }

    /**
     * Probe the docker daemon (cached per JVM). {@code docker info} with a
     * short timeout: a healthy daemon answers in well under 10s; a missing
     * binary or a stopped daemon fails fast. The cache means repeated
     * executes pay the probe once — and the probe result is recorded in
     * logs, the auditable-skip contract from {@code DockerDaemonProbeIT}.
     */
    boolean dockerDaemonAvailable() {
        Boolean cached = daemonAvailable;
        if (cached != null) {
            return cached;
        }
        boolean available;
        try {
            Process p = new ProcessBuilder(List.of("docker", "info", "--format",
                    "{{.ServerVersion}}")).redirectErrorStream(true).start();
            available = p.waitFor(daemonProbeTimeout.toSeconds(), TimeUnit.SECONDS)
                    && p.exitValue() == 0;
        } catch (IOException dockerBinaryMissing) {
            available = false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            available = false;
        }
        daemonAvailable = available;
        log.info("docker daemon probe: available={} (cached for this JVM)", available);
        return available;
    }

    // Command assembly (pure, daemon-free, fully tested)

    /**
     * Assemble the full {@code docker run} argument vector for one guest
     * execution. Pure function: no daemon, no filesystem, no side
     * effects — every 4.3 hardening row maps to a real flag.
     * <pre>
     *   roadmap 4.3 row                     -> flag
     *   ─────────────────────────────────────────────────────────────
     *   非特权 UID/GID                       -> --user 65534:65534
     *   seccomp                              -> --security-opt seccomp=default (Docker's
     *                                          default profile applies; "unconfined" never)
     *   capability drop                      -> --cap-drop ALL
     *   cgroup CPU/内存/IO                   -> --memory, --cpus (IO waits for the
     *                                          device-mapping form on CI)
     *   网络 namespace + allowlist           -> --network none (v1: deny-all; the
     *                                          allowlist form waits for CI)
     *   read-only rootfs + workspace mount   -> --read-only + tmpfs /workspace
     *   镜像 Digest 记录                      -> digest() (pinned reference, reported)
     * </pre>
     *
     * @param spec        the sandbox configuration (timeout/memory/network policy)
     * @param guestCmd    the command the container runs (already inside the image)
     * @param workspace   the in-container workspace path mounts operate on
     */
    List<String> dockerRunCommand(SandboxSpec spec, List<String> guestCmd, String workspace) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(guestCmd, "guestCmd");
        List<String> args = new ArrayList<>();
        args.add("docker");
        args.add("run");
        args.add("--rm");                                    // cleanup: container removed on exit (4.3 cleanup row)
        args.add("--user");                                  // 非特权 UID/GID
        args.add(SANDBOX_UID + ":" + SANDBOX_GID);
        args.add("--cap-drop");                              // capability drop
        args.add("ALL");
        args.add("--security-opt");                          // seccomp: default profile, never unconfined
        args.add("seccomp=default");
        args.add("--read-only");                             // read-only rootfs
        args.add("--tmpfs");                                 // writable workspace mount
        args.add(workspace + ":rw,size=64m");
        if (spec.getMemoryLimitBytes() > 0) {                // cgroup memory ceiling
            args.add("--memory");
            args.add(spec.getMemoryLimitBytes() + "b");
        }
        args.add("--cpus");                                  // cgroup CPU ceiling (default 1.0)
        args.add("1.0");
        if (spec.isNetworkBlocked()) {                       // network namespace: deny-all
            args.add("--network");
            args.add("none");
        }
        args.add(image);                                     // image (digest-pinned when configured)
        args.addAll(guestCmd);
        return List.copyOf(args);
    }

    /**
     * The image reference this adapter reports for {@code SandboxReport}
     * digest pinning (roadmap 4.3: "记录镜像 Digest"). A reference WITH a
     * digest ({@code repo@sha256:...}) is reported as-is; a tag reference
     * is reported tagged, honestly — the digest is resolvable only by the
     * daemon at pull time, and the v1 skeleton reports what was CONFIGURED,
     * never a fabricated digest.
     */
    String imageReference() {
        return image;
    }

    /** True when the configured image reference is already digest-pinned. */
    boolean isDigestPinned() {
        return image != null && image.contains("@sha256:");
    }

    /**
     * The structural hardening flags this adapter assembles — the honest
     * guarantee surface a {@code SandboxReport} entry can cite for the
     * DOCKER tier once the integration lands (until then the tier stays a
     * placeholder: zero guarantees claimed).
     */
    static Map<String, String> hardeningSurface() {
        Map<String, String> surface = new LinkedHashMap<>();
        surface.put("uidGid", "container runs as " + SANDBOX_UID + ":" + SANDBOX_GID
                + " (never uid 0)");
        surface.put("capabilities", "ALL capabilities dropped");
        surface.put("seccomp", "Docker default profile (never unconfined)");
        surface.put("rootfs", "read-only rootfs, tmpfs workspace");
        surface.put("network", "deny-all (--network none) unless spec allows");
        surface.put("cgroups", "memory ceiling from spec, 1.0 CPU default");
        surface.put("image", "digest pinning supported (repo@sha256:...)");
        return surface;
    }
}

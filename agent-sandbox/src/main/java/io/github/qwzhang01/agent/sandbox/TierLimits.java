package io.github.qwzhang01.agent.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 4.2: the tier table as EXECUTABLE data, not prose.
 * <p>
 * {@link SandboxPolicy} answers "WHICH tier for this risk"; this class answers
 * "WHAT does that tier actually enforce". v1 had the limits scattered as
 * javadoc prose and spec defaults - a caller wanting "what exactly does
 * PROCESS enforce for UNTRUSTED code?" had to read three files. {@code TierLimits}
 * turns the per-tier limit table into one queryable record with builder
 * defaults, so policy review, red-team reports, and the DOCKER adapter all
 * read the SAME source of truth.
 * <p>
 * Defaults are deny-by-default aligned (Stage 4.2):
 * <ul>
 *   <li>network: denied at every tier below DOCKER (guest guard + tier
 *       boundary at DOCKER+ via network namespace).</li>
 *   <li>filesystem: WORKSPACE_ONLY below DOCKER (guest guard); read-only
 *       rootfs + writable workspace mount at DOCKER+.</li>
 *   <li>process spawn: denied below DOCKER (FilePermission execute); allowed
 *       only within the container at DOCKER+.</li>
 *   <li>resource ceilings: timeout/memory/output caps with spec defaults;
 *       DOCKER+ adds cgroup CPU/IO caps on top.</li>
 * </ul>
 */
public final class TierLimits {

    /**
     * Executable limits for one (tier, risk) pair.
     *
     * @param tier          the isolation tier these limits apply to
     * @param riskLevel     the risk classification that selected the tier
     * @param networkDenied whether outbound network access is denied
     * @param fileAccess    guest filesystem access policy
     * @param processSpawnDenied whether spawning child processes is denied
     * @param timeout       wall-clock ceiling for one execution
     * @param memoryLimitBytes heap ceiling (-Xmx / cgroup memory)
     * @param outputLimitBytes per-stream stdout/stderr capture cap
     * @param cpuLimitDescription CPU enforcement description (spec floor or
     *                      cgroup at DOCKER+); recorded as text, enforced
     *                      structurally where the tier supports it
     * @param requiresApproval whether executions at this (tier, risk) need
     *                      human approval or a stronger sandbox first
     *                      (Stage 4.2: high-risk executes must pass approval
     *                      or escalate)
     */
    public record Limits(
            SandboxTier tier,
            SandboxRiskLevel riskLevel,
            boolean networkDenied,
            SandboxSpec.FileAccessPolicy fileAccess,
            boolean processSpawnDenied,
            Duration timeout,
            long memoryLimitBytes,
            long outputLimitBytes,
            String cpuLimitDescription,
            boolean requiresApproval
    ) {
    }

    private TierLimits() {
    }

    /** Default wall-clock ceiling per tier below DOCKER. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    /** Default heap ceiling (256 MB, matches SandboxSpec default). */
    public static final long DEFAULT_MEMORY_BYTES = 256L * 1024 * 1024;

    /** Default per-stream output cap (1 MB, matches SandboxSpec default). */
    public static final long DEFAULT_OUTPUT_BYTES = 1024L * 1024;

    /** Tiers whose isolation is structural (namespace/cgroup-based). */
    public static final List<SandboxTier> STRUCTURAL_TIERS =
            List.of(SandboxTier.DOCKER, SandboxTier.MICROVMM);

    /**
     * The limit table for one tier. Lower tiers enforce in-guest (guard);
     * structural tiers enforce at the OS boundary - the table records which.
     */
    public static Limits limitsFor(SandboxTier tier, SandboxRiskLevel risk) {
        boolean structural = STRUCTURAL_TIERS.contains(tier);
        boolean highRisk = risk == SandboxRiskLevel.ADVERSARIAL;
        return new Limits(
                tier,
                risk,
                /* networkDenied */ true,
                /* fileAccess */ SandboxSpec.FileAccessPolicy.WORKSPACE_ONLY,
                /* processSpawnDenied */ !structural,
                DEFAULT_TIMEOUT,
                DEFAULT_MEMORY_BYTES,
                DEFAULT_OUTPUT_BYTES,
                structural ? "cgroup cpu.max (enforced at container boundary)"
                        : "spec floor only (no CPU cap below DOCKER; timeout + memory are the ceiling)",
                /* requiresApproval */ highRisk
        );
    }

    /**
     * Map a risk level + tenancy to the executable limits of the tier the
     * default policy selects (convenience: policy.tierFor + limitsFor).
     */
    public static Limits forRisk(SandboxRiskLevel risk, boolean multiTenant) {
        return limitsFor(SandboxPolicy.defaultPolicy().tierFor(risk, multiTenant), risk);
    }

    /**
     * Render the full limit matrix (policy review / docs table).
     */
    public static Map<SandboxTier, Limits> fullTable() {
        return Map.of(
                SandboxTier.CLASSLOADER, limitsFor(SandboxTier.CLASSLOADER, SandboxRiskLevel.SEMI_TRUSTED),
                SandboxTier.PROCESS, limitsFor(SandboxTier.PROCESS, SandboxRiskLevel.UNTRUSTED),
                SandboxTier.DOCKER, limitsFor(SandboxTier.DOCKER, SandboxRiskLevel.ADVERSARIAL),
                SandboxTier.MICROVMM, limitsFor(SandboxTier.MICROVMM, SandboxRiskLevel.ADVERSARIAL),
                SandboxTier.WASM, limitsFor(SandboxTier.WASM, SandboxRiskLevel.ADVERSARIAL)
        );
    }
}

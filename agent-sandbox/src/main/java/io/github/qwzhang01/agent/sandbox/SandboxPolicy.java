package io.github.qwzhang01.agent.sandbox;

/**
 * Maps {@link SandboxRiskLevel} to a {@link SandboxTier} with documented rationale (KP9).
 * <p>
 * The policy encodes the risk / latency / escape-surface triangle as explicit product
 * decisions, not as implicit convention. Callers can override the default policy by
 * constructing their own {@code SandboxPolicy} instance.
 * <p>
 * Default policy table:
 * <pre>
 *   Risk Level Tier Rationale
 *   ──────────────────────────────────────────────────────────────────────────
 *   TRUSTED CLASSLOADER Same trust domain; no security boundary needed.
 *                                 ClassLoader used for output capture + timeout only.
 *
 *   SEMI_TRUSTED CLASSLOADER Decision 21: LLM-generated code is non-adversarial.
 *   (single-tenant) ClassLoader blocks common dangerous APIs at load time
 *                                 (Runtime, File, ProcessBuilder, reflection).
 *                                 Fast path; escalator auto-upgrades if code is blocked.
 *
 *   SEMI_TRUSTED PROCESS Decision 21 breaks in multi-tenant: one user's prompt
 *   (multi-tenant) can instruct the LLM to try escape techniques. The
 *                                 process boundary prevents cross-tenant contamination.
 *
 *   UNTRUSTED PROCESS User-submitted code; may be intentionally malicious.
 *                                 Process boundary prevents JVM heap access. OS-level
 *                                 filesystem isolation is still the operator's concern.
 *
 *   ADVERSARIAL PROCESS Minimum viable tier in v1. Production deployments at
 *                                 this level should add Docker / Firecracker / seccomp.
 * </pre>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * SandboxPolicy policy = SandboxPolicy.defaultPolicy;
 * SandboxTier tier = policy.tierFor(SandboxRiskLevel.SEMI_TRUSTED, false);
 * // -> CLASSLOADER (single-tenant, Decision 21 holds)
 *
 * SandboxTier multiTenantTier = policy.tierFor(SandboxRiskLevel.SEMI_TRUSTED, true);
 * // -> PROCESS (Decision 21 boundary crossed)
 * }</pre>
 */
public class SandboxPolicy {

    private static final SandboxPolicy DEFAULT = new SandboxPolicy();

    /** Returns the shared default policy instance. */
    public static SandboxPolicy defaultPolicy() {
        return DEFAULT;
    }

    /**
     * Select the sandbox tier for the given risk level, assuming single-tenant execution.
     * <p>
     * Equivalent to {@code tierFor(riskLevel, false)}.
     */
    public SandboxTier tierFor(SandboxRiskLevel riskLevel) {
        return tierFor(riskLevel, false);
    }

    /**
     * Select the sandbox tier for the given risk level and tenancy mode.
     *
     * @param riskLevel risk classification of the code to execute
     * @param multiTenant {@code true} when multiple untrusted users share the same
     *                     sandbox host (breaks Decision 21 for {@link SandboxRiskLevel#SEMI_TRUSTED})
     * @return the minimum recommended {@link SandboxTier} for this risk profile
     */
    public SandboxTier tierFor(SandboxRiskLevel riskLevel, boolean multiTenant) {
        return switch (riskLevel) {
            case TRUSTED -> SandboxTier.CLASSLOADER;
            case SEMI_TRUSTED -> multiTenant ? SandboxTier.PROCESS : SandboxTier.CLASSLOADER;
            case UNTRUSTED, ADVERSARIAL -> SandboxTier.PROCESS;
        };
    }

    /**
     * Whether the default policy recommends optimistic escalation for this risk level.
     * <p>
     * Optimistic escalation: try ClassLoader first (fast), escalate to Process when
     * ClassLoader blocks the code. This makes sense for {@link SandboxRiskLevel#SEMI_TRUSTED}
     * single-tenant: most LLM-generated code is safe and runs fast; only dangerous
     * code causes a ClassLoader block, which then warrants full process isolation.
     * <p>
     * {@link SandboxRiskLevel#UNTRUSTED} and {@link SandboxRiskLevel#ADVERSARIAL}
     * skip the ClassLoader attempt entirely and go straight to Process — the code is
     * already assumed dangerous, trying ClassLoader first wastes a compilation round-trip.
     *
     * @param riskLevel risk classification
     * @param multiTenant tenancy context
     */
    public boolean useOptimisticEscalation(SandboxRiskLevel riskLevel, boolean multiTenant) {
        return riskLevel == SandboxRiskLevel.SEMI_TRUSTED && !multiTenant;
    }
}

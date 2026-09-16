package io.github.qwzhang01.agent.sandbox;

import io.github.qwzhang01.agent.sandbox.classloader.ClassLoaderSandbox;
import io.github.qwzhang01.agent.sandbox.process.ProcessSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sandbox implementation that selects and optionally escalates between tiers (KP9).
 * <p>
 * Two execution modes, controlled by {@link SandboxPolicy#useOptimisticEscalation}:
 *
 * <h2>Optimistic (SEMI_TRUSTED + single-tenant)</h2>
 * <ol>
 *   <li>Try {@link ClassLoaderSandbox} first — fast, no JVM startup cost.</li>
 *   <li>If the result is BLOCKED (ClassLoader refused a dangerous class access):
 *       escalate to {@link ProcessSandbox} — the code IS trying to do something
 *       dangerous, so we need real isolation before letting it run.</li>
 *   <li>Other failure modes (timeout, error, success) are returned as-is.</li>
 * </ol>
 * The optimistic path is the right default for LLM-generated code: most code the
 * model writes is benign arithmetic / string manipulation. Only the rare dangerous
 * snippet triggers escalation, paying the JVM startup cost only when necessary.
 *
 * <h2>Escalation budget (熔断, KP9 thinking-question 2 — per-run ledger, debt-2 fix 2026-09-12)</h2>
 * The optimistic loop's cost model breaks when the SAME caller keeps submitting
 * block-triggering code: every escalation pays a double compile + JVM startup
 * (1–2 s), so a chatty adversarial-or-just-buggy source turns the fast path into
 * a denial-of-wallet. {@link #DEFAULT_ESCALATION_BUDGET} caps escalations per
 * <b>attribution key</b>: when the {@link SandboxSpec spec} carries a
 * {@code runId}, the budget is accounted PER RUN (one buggy run burning its own
 * budget cannot dilute another run's — the per-caller trust this budget was
 * designed to meter); a spec without a {@code runId} falls back to the
 * pre-fix instance-level ledger so unattributed callers keep the old behavior.
 * Once a key's budget is spent, subsequent BLOCKED results under that key are
 * returned as-is (fail-closed to the cheap refusal) instead of escalating.
 *
 * <h2>Direct (UNTRUSTED / ADVERSARIAL / multi-tenant)</h2>
 * Route straight to the tier mandated by {@link SandboxPolicy#tierFor}. For
 * UNTRUSTED/ADVERSARIAL the ClassLoader attempt would be wasted: we already know
 * the code should be treated as dangerous.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * // Default: optimistic for SEMI_TRUSTED single-tenant
 * Sandbox sandbox = SandboxEscalator.forRisk(SandboxRiskLevel.SEMI_TRUSTED);
 * SandboxResult result = sandbox.execute("Generated", code);
 *
 * // Multi-tenant coding agent: straight to Process
 * Sandbox sandbox = SandboxEscalator.forRisk(SandboxRiskLevel.UNTRUSTED);
 * }</pre>
 */
public class SandboxEscalator implements Sandbox {

    private static final Logger log = LoggerFactory.getLogger(SandboxEscalator.class);

    /** Default lifetime escalation budget per attribution key. */
    public static final int DEFAULT_ESCALATION_BUDGET = 3;

    private final Sandbox fastSandbox;
    private final Sandbox strongSandbox;
    private final SandboxRiskLevel riskLevel;
    private final SandboxPolicy policy;
    private final boolean multiTenant;
    private final int escalationBudget;

    /**
     * Per-runId ledger (debt-2 fix): one budget per attributed run. The
     * map grows with the number of distinct runIds seen — bounded by
     * caller behavior, same class of state as {@code activeRuns} in
     * RunManager (long-lived service hosts both).
     */
    private final java.util.concurrent.ConcurrentHashMap<String, AtomicInteger> budgetByRunId =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Fallback ledger for unattributed specs (no runId): the pre-fix instance-level behavior. */
    private final AtomicInteger unattributedEscalations = new AtomicInteger();

    // ============ Constructors ============

    /**
     * Full constructor (source-compatible shape, widened to the
     * {@link Sandbox} interface so tests can inject scripted tiers).
     *
     * @param fastSandbox   the fast tier (typically {@link ClassLoaderSandbox})
     * @param strongSandbox the strong tier (typically {@link ProcessSandbox})
     * @param riskLevel     caller's risk assessment of the code to execute
     * @param policy        tier-selection policy
     * @param multiTenant   whether multiple untrusted users share this escalator
     */
    public SandboxEscalator(Sandbox fastSandbox,
                            Sandbox strongSandbox,
                            SandboxRiskLevel riskLevel,
                            SandboxPolicy policy,
                            boolean multiTenant) {
        this(fastSandbox, strongSandbox, riskLevel, policy, multiTenant,
                DEFAULT_ESCALATION_BUDGET);
    }

    /**
     * Full constructor with an explicit escalation budget.
     *
     * @param escalationBudget lifetime cap on optimistic escalations; &lt;= 0
     *                         disables escalation entirely (BLOCKED returns as-is)
     */
    public SandboxEscalator(Sandbox fastSandbox,
                            Sandbox strongSandbox,
                            SandboxRiskLevel riskLevel,
                            SandboxPolicy policy,
                            boolean multiTenant,
                            int escalationBudget) {
        this.fastSandbox = Objects.requireNonNull(fastSandbox, "fastSandbox must not be null");
        this.strongSandbox = Objects.requireNonNull(strongSandbox, "strongSandbox must not be null");
        this.riskLevel = Objects.requireNonNull(riskLevel, "riskLevel must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.multiTenant = multiTenant;
        this.escalationBudget = escalationBudget;
    }

    // ============ Factory helpers ============

    /**
     * Create an escalator with default sandboxes and default policy.
     *
     * @param riskLevel risk level of the code; drives tier selection
     */
    public static SandboxEscalator forRisk(SandboxRiskLevel riskLevel) {
        return forRisk(riskLevel, false);
    }

    /**
     * Create an escalator with default sandboxes and default policy, with tenancy control.
     *
     * @param riskLevel   risk level of the code
     * @param multiTenant {@code true} when multiple untrusted users share this instance
     */
    public static SandboxEscalator forRisk(SandboxRiskLevel riskLevel, boolean multiTenant) {
        return new SandboxEscalator(
                new ClassLoaderSandbox(),
                new ProcessSandbox(),
                riskLevel,
                SandboxPolicy.defaultPolicy(),
                multiTenant
        );
    }

    // ============ Sandbox interface ============

    @Override
    public SandboxResult execute(String className, String code) {
        return execute(className, code, SandboxSpec.builder().build());
    }

    @Override
    public SandboxResult execute(String className, String code, SandboxSpec spec) {
        if (policy.useOptimisticEscalation(riskLevel, multiTenant)) {
            return optimisticExecute(className, code, spec);
        }
        return directExecute(className, code, spec);
    }

    // ============ Execution strategies ============

    /**
     * Optimistic: try the fast tier; escalate to the strong tier on a
     * BLOCKED result, while the escalation budget lasts.
     * <p>
     * BLOCKED means the code tried to load a class the fast tier's policy
     * refuses (e.g. java.lang.Runtime) — the signal that the code IS
     * dangerous and deserves real isolation rather than a refusal.
     * <p>
     * Other failures (timeout, error, success) are returned as-is —
     * they are not escalation signals.
     * <p>
     * Budget accounting is keyed by {@code spec.getRunId()} (debt-2 fix):
     * an attributed run burns only its own budget; an unattributed spec
     * falls back to the shared instance-level counter.
     */
    private SandboxResult optimisticExecute(String className, String code, SandboxSpec spec) {
        SandboxResult fast = fastSandbox.execute(className, code, spec);
        if (fast.success()) {
            log.debug("[SandboxEscalator] Fast tier succeeded for '{}'", className);
            return fast;
        }
        if (isBlocked(fast)) {
            String runId = spec != null ? spec.getRunId() : null;
            boolean withinBudget = tryAcquireBudget(runId);
            if (!withinBudget) {
                log.warn("[SandboxEscalator] Escalation budget ({} for '{}', key={}) spent; "
                                + "returning the BLOCKED result as-is",
                        escalationBudget, className, runId != null ? runId : "<instance>");
                return fast;
            }
            log.warn("[SandboxEscalator] Fast tier blocked '{}' ({}); escalating (used {}/{} for key={})",
                    className, fast.error(), usedFor(runId), escalationBudget,
                    runId != null ? runId : "<instance>");
            SandboxResult escalated = strongSandbox.execute(className, code, spec);
            log.debug("[SandboxEscalator] Strong tier result for '{}': success={}",
                    className, escalated.success());
            return escalated;
        }
        // Timeout or other error: return the fast-tier result as-is
        return fast;
    }

    // ============ Budget ledger ============

    /**
     * Reserve one escalation slot for the given attribution key.
     * Runs {@code budget} or fewer escalations; beyond that, fail-closed.
     * The counter only counts REAL escalations (a rejected attempt
     * leaves it untouched) — the metric stays honest for monitoring.
     */
    private boolean tryAcquireBudget(String runId) {
        if (runId == null || runId.isBlank()) {
            return acquireSlot(unattributedEscalations);
        }
        AtomicInteger used = budgetByRunId.computeIfAbsent(runId, k -> new AtomicInteger());
        return acquireSlot(used);
    }

    /** Bounded claim via CAS: every successful claim is a real escalation. */
    private boolean acquireSlot(AtomicInteger counter) {
        int current;
        do {
            current = counter.get();
            if (current >= escalationBudget) {
                return false;
            }
        } while (!counter.compareAndSet(current, current + 1));
        return true;
    }

    /** Escalations consumed so far for the given attribution key. */
    private int usedFor(String runId) {
        if (runId == null || runId.isBlank()) {
            return unattributedEscalations.get();
        }
        return budgetByRunId.getOrDefault(runId, new AtomicInteger()).get();
    }

    /**
     * Direct: route straight to the tier mandated by policy, no fast-tier attempt.
     */
    private SandboxResult directExecute(String className, String code, SandboxSpec spec) {
        SandboxTier tier = policy.tierFor(riskLevel, multiTenant);
        log.debug("[SandboxEscalator] Direct execution via {} for risk={}, multiTenant={}",
                tier, riskLevel, multiTenant);
        return switch (tier) {
            case CLASSLOADER -> fastSandbox.execute(className, code, spec);
            case PROCESS -> strongSandbox.execute(className, code, spec);
            default -> throw new UnsupportedOperationException(
                    "Sandbox tier " + tier + " is not implemented in v1. "
                            + "See SandboxTier javadoc for upgrade triggers.");
        };
    }

    // ============ Helpers ============

    /**
     * Returns {@code true} if the result represents a fast-tier block event.
     * <p>
     * Uses the structured {@link SandboxResult.FailureKind} when present
     * (BLOCKED_BY_POLICY), falling back to the "Blocked:" error prefix for
     * results constructed before the kind existed.
     */
    static boolean isBlocked(SandboxResult result) {
        if (result.kind() == SandboxResult.FailureKind.BLOCKED_BY_POLICY) {
            return true;
        }
        return !result.success()
                && !result.timedOut()
                && result.error() != null
                && result.error().startsWith("Blocked:");
    }

    // ============ Accessors ============

    public SandboxRiskLevel getRiskLevel() {
        return riskLevel;
    }

    public SandboxPolicy getPolicy() {
        return policy;
    }

    public boolean isMultiTenant() {
        return multiTenant;
    }

    /**
     * Escalations consumed against the instance-level fallback ledger
     * (unattributed specs). Compatibility view of the pre-fix counter —
     * monitoring dashboards that read this keep working. For run-scoped
     * observability use {@link #getEscalationsUsed(String)}.
     */
    public int getEscalationsUsed() {
        return unattributedEscalations.get();
    }

    /** Escalations consumed so far for the given runId (0 if never seen). */
    public int getEscalationsUsed(String runId) {
        if (runId == null || runId.isBlank()) {
            return unattributedEscalations.get();
        }
        return budgetByRunId.getOrDefault(runId, new AtomicInteger()).get();
    }

    /** Distinct attributed runs this escalator has seen (ledger size). */
    public int getTrackedRunCount() {
        return budgetByRunId.size();
    }
}

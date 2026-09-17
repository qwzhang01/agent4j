package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 4.x (2026-09-17): the escalation budget ledger is two-tier —
 * tenant ledger first (a shared cap across all of one tenant's runs),
 * then the per-run ledger. A noisy tenant cannot dilute other tenants'
 * budgets even when its own individual runs each have budget left.
 * <p>
 * Why tenant first: the run ledger answers "is this run chatty?" while
 * the tenant ledger answers "is this customer chatty?" — the isolation
 * boundary the budget exists to protect is the tenant, not the run.
 */
class SandboxEscalationBudgetByTenantTest {

    /** A scripted fast tier: always BLOCKED, never runs anything. */
    static final class AlwaysBlockedSandbox implements Sandbox {
        @Override
        public SandboxResult execute(String className, String code) {
            return SandboxResult.blocked("java.lang.Runtime");
        }

        @Override
        public SandboxResult execute(String className, String code, SandboxSpec spec) {
            return SandboxResult.blocked("java.lang.Runtime");
        }
    }

    /** A scripted strong tier: counts invocations, always succeeds. */
    static final class CountingSandbox implements Sandbox {
        int calls;

        @Override
        public SandboxResult execute(String className, String code) {
            calls++;
            return SandboxResult.success("strong:" + calls);
        }

        @Override
        public SandboxResult execute(String className, String code, SandboxSpec spec) {
            calls++;
            return SandboxResult.success("strong:" + calls);
        }
    }

    private static SandboxSpec specWith(String tenantId, String runId) {
        SandboxSpec.Builder b = SandboxSpec.builder().runId(runId);
        if (tenantId != null) {
            b.tenantId(tenantId);
        }
        return b.build();
    }

    @Test
    @DisplayName("tenant budget is shared across its runs: exhausting it via one run blocks another")
    void tenantBudgetIsSharedAcrossRuns() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        // Budget 2: tenant-level shared, NOT per-run when a tenant is present.
        // multiTenant=false keeps the OPTIMISTIC path active (the budget ledger
        // lives there); tenancy attribution rides on SandboxSpec.tenantId.
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 2);

        // run-1 of tenant-a spends 2 escalations: the whole tenant budget.
        assertTrue(escalator.execute("G", "c", specWith("tenant-a", "run-1")).success());
        assertTrue(escalator.execute("G", "c", specWith("tenant-a", "run-1")).success());

        // run-2 of the SAME tenant: refused even though this run itself has
        // burned nothing — the tenant ledger is spent.
        SandboxResult r = escalator.execute("G", "c", specWith("tenant-a", "run-2"));
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind(),
                "tenant budget spent: run-2 of the same tenant is fail-closed");
        assertEquals(2, strong.calls, "no 3rd JVM started for tenant-a");

        // Another tenant on the SAME escalator: fresh tenant ledger.
        assertTrue(escalator.execute("G", "c", specWith("tenant-b", "run-x")).success(),
                "tenant-b has its own ledger - tenant-a's burnout must not dilute it");
        assertEquals(3, strong.calls);
        assertEquals(2, escalator.getEscalationsUsedByTenant("tenant-a"));
        assertEquals(1, escalator.getEscalationsUsedByTenant("tenant-b"));
        assertEquals(2, escalator.getTrackedTenantCount());
    }

    @Test
    @DisplayName("run-only attribution keeps the per-run ledger (tenant absent falls back to run)")
    void runOnlyAttributionKeepsPerRunLedger() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 1);

        // run-1 (no tenant): burns its own single-slot budget.
        assertTrue(escalator.execute("G", "c", specWith(null, "run-1")).success());
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY,
                escalator.execute("G", "c", specWith(null, "run-1")).kind());

        // run-2 (no tenant): unaffected by run-1's burnout.
        assertTrue(escalator.execute("G", "c", specWith(null, "run-2")).success());
        assertEquals(2, strong.calls);
    }
}

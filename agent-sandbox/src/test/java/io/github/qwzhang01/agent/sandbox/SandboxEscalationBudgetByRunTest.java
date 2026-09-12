package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debt-2 tests (KP9 follow-up, 2026-09-12): the escalation budget is
 * accounted per attribution key, not per escalator instance.
 * <p>
 * The debt: a long-lived escalator shared by multiple runs let one
 * chatty-blocked run burn everyone's budget (deny-of-service for the
 * well-behaved). The fix: {@code SandboxSpec.runId} is the ledger key —
 * each attributed run gets its own budget; unattributed specs fall back
 * to the shared instance counter (the pre-fix behavior, kept so legacy
 * call sites compile and behave identically).
 */
class SandboxEscalationBudgetByRunTest {

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

    private static SandboxSpec specWithRun(String runId) {
        return SandboxSpec.builder().runId(runId).build();
    }

    @Test
    @DisplayName("per-run budget: one run exhausting its budget does not dilute another run's")
    void runBurnsOnlyItsOwnBudget() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 3);

        // Run-A burns its whole budget (3 escalations), then gets fail-closed.
        for (int i = 1; i <= 3; i++) {
            SandboxResult r = escalator.execute("Generated", "code", specWithRun("run-A"));
            assertTrue(r.success(), "run-A escalation " + i + " reaches the strong tier");
        }
        SandboxResult a4 = escalator.execute("Generated", "code", specWithRun("run-A"));
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, a4.kind(),
                "run-A budget spent: fail-closed to the cheap refusal");
        assertEquals(3, strong.calls, "run-A's 4th attempt must not start another JVM");

        // Run-B on the SAME escalator: fresh budget, escalates normally.
        SandboxResult b1 = escalator.execute("Generated", "code", specWithRun("run-B"));
        assertTrue(b1.success(), "run-B has its own budget - run-A's burnout must not dilute it");
        assertEquals(4, strong.calls);
        assertEquals(3, escalator.getEscalationsUsed("run-A"));
        assertEquals(1, escalator.getEscalationsUsed("run-B"));
        assertEquals(2, escalator.getTrackedRunCount());
    }

    @Test
    @DisplayName("unattributed specs keep the legacy shared-instance budget")
    void unattributedSpecsFallBackToInstanceBudget() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 2);

        // No runId anywhere: the shared counter applies across calls.
        for (int i = 1; i <= 2; i++) {
            SandboxResult r = escalator.execute("Generated", "code");
            assertTrue(r.success(), "legacy path escalation " + i);
        }
        SandboxResult third = escalator.execute("Generated", "code");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, third.kind(),
                "legacy shared budget spent: the 3rd block returns as-is");
        assertEquals(2, strong.calls);
        assertEquals(2, escalator.getEscalationsUsed(),
                "the compatibility counter reflects unattributed usage");
        assertEquals(0, escalator.getTrackedRunCount(),
                "no runId means no per-run ledger entries");
    }

    @Test
    @DisplayName("mixed traffic: attributed runs isolated, unattributed traffic in its own lane")
    void mixedAttributionLanesAreIndependent() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 1);

        // Attributed run exhausts its single-slot budget.
        escalator.execute("Generated", "c", specWithRun("run-1"));
        SandboxResult run1Second = escalator.execute("Generated", "c", specWithRun("run-1"));
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, run1Second.kind());

        // Unattributed traffic still escalates (its own lane).
        SandboxResult anon = escalator.execute("Generated", "c");
        assertTrue(anon.success(), "unattributed lane unaffected by run-1's burnout");
        // A second attributed run is also unaffected.
        SandboxResult run2 = escalator.execute("Generated", "c", specWithRun("run-2"));
        assertTrue(run2.success());
        assertEquals(3, strong.calls);
    }
}

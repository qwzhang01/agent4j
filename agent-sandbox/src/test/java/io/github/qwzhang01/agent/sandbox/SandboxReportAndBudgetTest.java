package io.github.qwzhang01.agent.sandbox;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SandboxReport} (KP9 · enforcement honesty: every
 * execution can declare what its tier guarantees / does not).
 * <p>
 * Plus the escalation-budget circuit breaker (KP9 thinking-question 2):
 * the optimistic loop must not turn one buggy-but-chatty source into a
 * denial-of-wallet via endless JVM startups.
 */
class SandboxReportAndBudgetTest {

    // SandboxReport: the honest translator

    @Test
    @DisplayName("CLASSLOADER success: guarantees the fast path, admits the escape surface")
    void classLoaderSuccessAdmitsEscapeSurface() {
        SandboxReport.Entry e = SandboxReport.report(
                SandboxTier.CLASSLOADER, SandboxResult.success("42"));
        assertTrue(e.guarantees().contains("in-process execution (no child JVM)"));
        assertTrue(e.notGuaranteed().stream().anyMatch(s -> s.contains("security boundary")),
                "the honest admission: ClassLoader is not a security boundary");
        assertNull(e.escalationNote());
    }

    @Test
    @DisplayName("CLASSLOADER blocked: the refusal itself is the guarantee")
    void classLoaderBlockedIsTheRefusal() {
        SandboxReport.Entry e = SandboxReport.report(
                SandboxTier.CLASSLOADER, SandboxResult.blocked("java.lang.Runtime"));
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, e.result().kind());
        assertNotNull(e.escalationNote(),
                "a block is a tier boundary event - the note says the code never ran");
        assertTrue(e.escalationNote().contains("never ran"));
    }

    @Test
    @DisplayName("PROCESS success: process guarantees listed, network/FS gaps admitted")
    void processSuccessAdmitsNetworkAndFsGaps() {
        SandboxReport.Entry e = SandboxReport.report(
                SandboxTier.PROCESS, SandboxResult.success("out", "err"));
        assertTrue(e.guarantees().stream().anyMatch(s -> s.contains("address space isolation")));
        assertTrue(e.notGuaranteed().stream().anyMatch(s -> s.contains("network isolation")));
        assertTrue(e.notGuaranteed().stream().anyMatch(s -> s.contains("filesystem whitelist")));
    }

    @Test
    @DisplayName("TIMEOUT at PROCESS: kill semantics still guaranteed (that IS the timeout)")
    void processTimeoutStillGuaranteesKill() {
        SandboxReport.Entry e = SandboxReport.report(
                SandboxTier.PROCESS, SandboxResult.timeout("partial"));
        assertTrue(e.guarantees().stream().anyMatch(s -> s.contains("forcible kill")),
                "destroyForcibly is the one thing a timeout PROVES");
        assertEquals(SandboxResult.FailureKind.TIMEOUT, e.result().kind());
    }

    @Test
    @DisplayName("CLASSLOADER timeout: honest admission - interrupt is cooperative, not a hard kill")
    void classLoaderTimeoutAdmitsCooperativeInterrupt() {
        SandboxReport.Entry e = SandboxReport.report(
                SandboxTier.CLASSLOADER, SandboxResult.timeout("partial"));
        assertTrue(e.notGuaranteed().stream()
                        .anyMatch(s -> s.contains("hard kill")),
                "the ClassLoader-tier honest gap: an interrupt-ignoring loop "
                        + "outlives the call - Process tier exists for exactly this");
    }

    @Test
    @DisplayName("placeholder tiers (DOCKER/MICROVM/WASM) report zero guarantees, loudly")
    void placeholderTiersClaimNothing() {
        for (SandboxTier tier : List.of(SandboxTier.DOCKER, SandboxTier.MICROVMM, SandboxTier.WASM)) {
            SandboxReport.Entry e = SandboxReport.report(tier, SandboxResult.success("x"));
            assertTrue(e.guarantees().isEmpty(),
                    tier + " is a placeholder - no guarantees may be claimed");
            assertEquals(1, e.notGuaranteed().size());
            assertNotNull(e.escalationNote());
        }
    }

    // Escalation budget (thinking-question 2)

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

    @Test
    @DisplayName("escalation budget: 3 escalations allowed, the 4th block is returned as-is")
    void budgetExhaustionReturnsBlockAsIs() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 3);

        for (int i = 1; i <= 3; i++) {
            SandboxResult r = escalator.execute("Generated", "code-" + i);
            assertTrue(r.success(), "escalation " + i + " runs in the strong tier");
        }
        assertEquals(3, strong.calls);
        assertEquals(3, escalator.getEscalationsUsed());

        SandboxResult fourth = escalator.execute("Generated", "code-4");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, fourth.kind(),
                "budget spent: the 4th block is returned as-is, no more JVM startups");
        assertEquals(3, strong.calls,
                "the strong tier was NOT invoked for the 4th attempt");
        assertTrue(fourth.error().startsWith("Blocked:"));
    }

    @Test
    @DisplayName("budget 0 disables escalation entirely (fail-closed to the cheap refusal)")
    void zeroBudgetDisablesEscalation() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 0);

        SandboxResult r = escalator.execute("Generated", "code");
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind());
        assertEquals(0, strong.calls, "no escalation may happen at budget 0");
    }

    @Test
    @DisplayName("escalationsUsed is visible for monitoring (deny-of-wallet signal)")
    void escalationsUsedIsObservable() {
        AlwaysBlockedSandbox fast = new AlwaysBlockedSandbox();
        CountingSandbox strong = new CountingSandbox();
        SandboxEscalator escalator = new SandboxEscalator(
                fast, strong, SandboxRiskLevel.SEMI_TRUSTED,
                SandboxPolicy.defaultPolicy(), false, 10);

        escalator.execute("Generated", "c1");
        escalator.execute("Generated", "c2");
        assertEquals(2, escalator.getEscalationsUsed(),
                "the counter is the monitoring hook: a host watching it climb "
                        + "knows the source is chatty-blocked before the budget caps it");
        assertEquals(2, strong.calls);
    }
}

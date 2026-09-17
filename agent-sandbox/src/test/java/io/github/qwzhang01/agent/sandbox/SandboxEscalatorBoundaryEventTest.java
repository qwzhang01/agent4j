package io.github.qwzhang01.agent.sandbox;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 4.4 (2026-09-17): the sandbox boundary's five emission points —
 * fast-tier success, budget refusal, escalation, strong-tier execution,
 * direct path — each produce a structural twin on the telemetry sink.
 * Guest code and outputs never ride the events.
 */
class SandboxEscalatorBoundaryEventTest {

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

    /** A fast tier that always succeeds: the optimistic fast path. */
    static final class AlwaysPassSandbox implements Sandbox {
        @Override
        public SandboxResult execute(String className, String code) {
            return SandboxResult.success("fast");
        }

        @Override
        public SandboxResult execute(String className, String code, SandboxSpec spec) {
            return SandboxResult.success("fast");
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
    @DisplayName("fast-tier success emits SandboxExecuted(CLASSLOADER)")
    void fastSuccessEmitsExecuted() {
        List<BoundaryEvent> events = new ArrayList<>();
        SandboxEscalator escalator = new SandboxEscalator(
                new AlwaysPassSandbox(), new CountingSandbox(),
                SandboxRiskLevel.SEMI_TRUSTED, SandboxPolicy.defaultPolicy(),
                false, 2, events::add);

        assertTrue(escalator.execute("G", "c", specWith(null, "run-1")).success());

        assertEquals(1, events.size());
        BoundaryEvent.SandboxExecuted e = (BoundaryEvent.SandboxExecuted) events.get(0);
        assertEquals("CLASSLOADER", e.tier());
        assertEquals("G", e.className());
        assertTrue(e.success());
        assertTrue(e.durationMs() >= 0);
    }

    @Test
    @DisplayName("budget exhaustion emits SandboxRefused(BUDGET_SPENT)")
    void budgetExhaustionEmitsRefused() {
        List<BoundaryEvent> events = new ArrayList<>();
        SandboxEscalator escalator = new SandboxEscalator(
                new AlwaysBlockedSandbox(), new CountingSandbox(),
                SandboxRiskLevel.SEMI_TRUSTED, SandboxPolicy.defaultPolicy(),
                false, 1, events::add);

        // Burn the single-slot budget.
        assertTrue(escalator.execute("G", "c", specWith("tenant-a", "run-1")).success());
        events.clear();

        SandboxResult r = escalator.execute("G", "c", specWith("tenant-a", "run-2"));
        assertEquals(SandboxResult.FailureKind.BLOCKED_BY_POLICY, r.kind());

        assertEquals(1, events.size());
        BoundaryEvent.SandboxRefused e = (BoundaryEvent.SandboxRefused) events.get(0);
        assertEquals("BUDGET_SPENT", e.refusalKind());
        assertEquals("G", e.className());
    }

    @Test
    @DisplayName("escalation emits SandboxEscalated with tenant attribution")
    void escalationEmitsEscalatedWithTenant() {
        List<BoundaryEvent> events = new ArrayList<>();
        SandboxEscalator escalator = new SandboxEscalator(
                new AlwaysBlockedSandbox(), new CountingSandbox(),
                SandboxRiskLevel.SEMI_TRUSTED, SandboxPolicy.defaultPolicy(),
                false, 2, events::add);

        assertTrue(escalator.execute("G", "c", specWith("tenant-a", "run-1")).success());

        // The escalation path emits TWO events: the escalation itself and
        // the strong-tier execution that followed it.
        BoundaryEvent.SandboxEscalated esc = events.stream()
                .filter(e -> e instanceof BoundaryEvent.SandboxEscalated)
                .map(e -> (BoundaryEvent.SandboxEscalated) e)
                .findFirst().orElseThrow();
        assertEquals("G", esc.className());
        assertEquals("tenant-a", esc.budgetUsedFor(),
                "tenant attribution rides the spec, not the constructor");
        assertEquals(1, events.stream()
                .filter(e -> e instanceof BoundaryEvent.SandboxExecuted
                        && "PROCESS".equals(((BoundaryEvent.SandboxExecuted) e).tier()))
                .count(), "strong-tier execution twin follows the escalation");
    }

    @Test
    @DisplayName("run-only attribution (no tenant) falls back to the run id")
    void escalationRunOnlyAttribution() {
        List<BoundaryEvent> events = new ArrayList<>();
        SandboxEscalator escalator = new SandboxEscalator(
                new AlwaysBlockedSandbox(), new CountingSandbox(),
                SandboxRiskLevel.SEMI_TRUSTED, SandboxPolicy.defaultPolicy(),
                false, 2, events::add);

        assertTrue(escalator.execute("G", "c", specWith(null, "run-9")).success());

        BoundaryEvent.SandboxEscalated esc = events.stream()
                .filter(e -> e instanceof BoundaryEvent.SandboxEscalated)
                .map(e -> (BoundaryEvent.SandboxEscalated) e)
                .findFirst().orElseThrow();
        assertEquals("run-9", esc.budgetUsedFor());
    }

    @Test
    @DisplayName("direct strong-tier path (multiTenant=true) emits SandboxExecuted with the named tier")
    void directPathEmitsNamedTier() {
        List<BoundaryEvent> events = new ArrayList<>();
        SandboxEscalator escalator = new SandboxEscalator(
                new AlwaysBlockedSandbox(), new CountingSandbox(),
                SandboxRiskLevel.UNTRUSTED, SandboxPolicy.defaultPolicy(),
                true, 2, events::add);

        assertTrue(escalator.execute("G", "c", specWith(null, "run-1")).success());

        assertEquals(1, events.size(), "direct path: one execution event, no escalation");
        BoundaryEvent.SandboxExecuted e = (BoundaryEvent.SandboxExecuted) events.get(0);
        assertEquals("PROCESS", e.tier(), "UNTRUSTED routes direct to the strong tier");
        assertTrue(e.success());
    }
}

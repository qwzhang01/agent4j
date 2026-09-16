package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.eval.EvalReport;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.version.ComponentVersion;
import io.github.qwzhang01.agent.observability.version.RunRecord;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.3 acceptance for drift detection and the unified report:
 * threshold alarms carry recommended actions; one document carries golden
 * set + online metrics + red team + version attribution.
 */
class DriftAndUnifiedReportTest {

    // ============ DriftDetector ============

    @Test
    void healthyWindowFiresNothing() {
        OnlineMetrics healthy = new OnlineMetrics(100, 95, 0.95, 1000, 500, 900, 950, 0.005, 0.01, null);
        DriftDetector.Thresholds thresholds = new DriftDetector.Thresholds()
                .minCompletionRate(0.90)
                .maxCostPerTaskMicros(2000)
                .maxLatencyP95Ms(1500)
                .maxSafetyViolationRate(0.02)
                .maxFallbackRate(0.05);

        assertTrue(DriftDetector.check(healthy, healthy, thresholds).isEmpty());
    }

    @Test
    void crossingLinesFiresAlarmsWithActions() {
        OnlineMetrics baseline = new OnlineMetrics(100, 95, 0.95, 1000, 500, 900, 950, 0.005, 0.01, null);
        OnlineMetrics drifted = new OnlineMetrics(100, 80, 0.80, 5000, 800, 2000, 2100, 0.06, 0.12, null);
        DriftDetector.Thresholds thresholds = new DriftDetector.Thresholds()
                .minCompletionRate(0.90)
                .maxCostPerTaskMicros(2000)
                .maxLatencyP95Ms(1500)
                .maxSafetyViolationRate(0.02)
                .maxFallbackRate(0.05);

        List<DriftDetector.DriftAlarm> alarms = DriftDetector.check(drifted, baseline, thresholds);

        assertEquals(5, alarms.size(), "every crossed line fires");
        for (DriftDetector.DriftAlarm alarm : alarms) {
            assertFalse(alarm.recommendedAction().isBlank(),
                    "an alarm without a recommended action is just a number: " + alarm.metric());
            assertFalse(alarm.threshold().isBlank());
        }
        // order: completion, cost, latency, safety, fallback
        assertEquals("taskCompletionRate", alarms.get(0).metric());
        assertEquals("costPerTaskMicros", alarms.get(1).metric());
        assertEquals("latencyP95Ms", alarms.get(2).metric());
        assertEquals("safetyViolationRate", alarms.get(3).metric());
        assertEquals("fallbackRate", alarms.get(4).metric());
        // deltas are current minus baseline
        assertEquals(-0.15, alarms.get(0).delta(), 1e-9);
        assertEquals(0.11, alarms.get(4).delta(), 1e-9);
        // the safety alarm points at injection review
        assertTrue(alarms.get(3).recommendedAction().contains("red team"));
    }

    @Test
    void unsetLinesAreNotWatched() {
        OnlineMetrics drifted = new OnlineMetrics(10, 1, 0.1, 99_999, 1, 2, 3, 0.9, 0.9, null);
        // only the completion floor is configured: everything else may burn
        List<DriftDetector.DriftAlarm> alarms = DriftDetector.check(
                drifted, drifted, new DriftDetector.Thresholds().minCompletionRate(0.9));
        assertEquals(1, alarms.size());
        assertEquals("taskCompletionRate", alarms.get(0).metric());
    }

    // ============ UnifiedEvalReport ============

    @Test
    void oneDocumentCarriesAllThreeSectionsPlusVersions() {
        EvalReport golden = EvalReport.of(
                List.of(new EvalReport.CaseResult("case-1", true, "passed"),
                        new EvalReport.CaseResult("case-2", true, "passed")),
                null, 0.5);
        OnlineMetrics online = new OnlineMetrics(50, 47, 0.94, 1200, 400, 800, 850, 0.0, 0.02, null);
        RunRecord combination = new RunRecord("run-1", "support",
                List.of(ComponentVersion.of(ComponentVersion.Kind.PROMPT, "support-system", "v3")),
                new RunMetrics("run-1", "support", AgentState.Status.DONE, null, 100,
                        1, 0, 0, 0, new ModelResponse.TokenUsage(1, 1, 2, 0), 0));

        UnifiedEvalReport report = new UnifiedEvalReport(
                "2026-09-16 release gate",
                List.of(combination),
                golden,
                online,
                new UnifiedEvalReport.RedTeamSummary(12, 11, "one partial leak, documented"));

        assertEquals(EvalReport.Verdict.BASELINE_ABSENT, report.goldenSet().verdict());
        assertEquals(0.94, report.onlineWindow().taskCompletionRate(), 1e-9);
        assertTrue(report.redTeam().run());
        assertEquals(11.0 / 12.0, report.redTeam().repelRate(), 1e-9);
        assertEquals("v3", report.primaryCombination().orElseThrow().versions().get(0).version());
        assertTrue(report.headline().contains("BASELINE_ABSENT"), report.headline());
        assertTrue(report.headline().contains("92% repelled"), report.headline());
    }

    @Test
    void notRunRedTeamIsAnHonestRow() {
        UnifiedEvalReport.RedTeamSummary notRun = UnifiedEvalReport.RedTeamSummary.notRun();
        assertFalse(notRun.run());
        assertEquals(0.0, notRun.repelRate(), 1e-9);
        assertTrue(notRun.notes().contains("not run"));
    }

    @Test
    void invalidInputsAreRejected() {
        EvalReport golden = EvalReport.of(
                List.of(new EvalReport.CaseResult("c", true, "ok")), null, 0.5);
        OnlineMetrics online = new OnlineMetrics(1, 1, 1.0, 0, 0, 0, 0, 0, 0, null);

        assertThrows(IllegalArgumentException.class, () -> new UnifiedEvalReport(
                " ", List.of(), golden, online, UnifiedEvalReport.RedTeamSummary.notRun()));
        assertThrows(IllegalArgumentException.class, () -> new UnifiedEvalReport.RedTeamSummary(
                5, 6, "more repelled than launched is a lie"));
    }
}

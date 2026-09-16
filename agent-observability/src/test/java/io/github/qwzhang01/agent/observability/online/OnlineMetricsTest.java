package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.3 acceptance for the online metrics definitions: the five-plus-one
 * indicators computed from real run rows, with the honest-blank memory rate
 * and the rejected empty window.
 */
class OnlineMetricsTest {

    @Test
    void computesTheFiveIndicatorsFromRunRows() {
        OnlineMetrics row = OnlineMetrics.from(List.of(
                run("r1", true, 1000, 300, 2, 0, 3, 0),   // done, cheap, clean
                run("r2", true, 2000, 500, 2, 0, 3, 0),   // done
                run("r3", false, 3000, 700, 2, 1, 3, 1),  // error: model error + denied tool
                run("r4", false, 4000, 900, 2, 0, 0, 0))); // error, no tools at all

        assertEquals(4, row.runs());
        assertEquals(2, row.completedRuns());
        assertEquals(0.5, row.taskCompletionRate(), 1e-9);
        // cost per ATTEMPTED task: (1000+2000+3000+4000)/4 = 2500, failures are not free
        assertEquals(2500, row.costPerTaskMicros());
        // sorted durations 300,500,700,900: p50 = 500 (ceil(0.5*4)=2nd), p95/p99 = 900
        assertEquals(500, row.latencyP50Ms());
        assertEquals(900, row.latencyP95Ms());
        assertEquals(900, row.latencyP99Ms());
        // denied tools: 1 of 9 total tool calls (3+3+3+0)
        assertEquals(1.0 / 9.0, row.safetyViolationRate(), 1e-9);
        // model errors: 1 of 8 model calls
        assertEquals(1.0 / 8.0, row.fallbackRate(), 1e-9);
    }

    @Test
    void maxStepsExceededIsNotCompletion() {
        OnlineMetrics row = OnlineMetrics.from(List.of(
                run("r1", false, 100, 100, 1, 0, 1, 0)));
        assertEquals(0, row.completedRuns());
        assertEquals(0.0, row.taskCompletionRate(), 1e-9);
    }

    @Test
    void memoryHitRateIsAnHonestBlankUntilTheBoundaryReports() {
        OnlineMetrics row = OnlineMetrics.from(List.of(
                run("r1", true, 100, 100, 1, 0, 1, 0)));
        assertEquals(null, row.memoryHitRate(), "no fabricated zero: null means unreported");
        assertFalse(row.memoryHitRateReported());
    }

    @Test
    void zeroToolCallsMeansZeroViolationRateNotNaN() {
        OnlineMetrics row = OnlineMetrics.from(List.of(
                run("r1", true, 100, 100, 1, 0, 0, 0)));
        assertEquals(0.0, row.safetyViolationRate(), 1e-9);
        assertEquals(0.0, row.fallbackRate(), 1e-9);
    }

    @Test
    void emptyWindowIsRejectedNotZeroed() {
        assertThrows(IllegalArgumentException.class, () -> OnlineMetrics.from(List.of()));
    }

    @Test
    void outOfRangeRatesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new OnlineMetrics(
                10, 5, 0.5, 100, 50, 90, 99, 1.5, 0.0, null));
        assertThrows(IllegalArgumentException.class, () -> new OnlineMetrics(
                10, 5, 0.5, 100, 50, 90, 99, 0.0, 0.0, 2.0));
        assertThrows(IllegalArgumentException.class, () -> new OnlineMetrics(
                5, 6, 1.2, 100, 50, 90, 99, 0.0, 0.0, null), "completedRuns > runs is a lie");
    }

    @Test
    void singleRunWindowHasAllQuantilesAtThatRun() {
        OnlineMetrics row = OnlineMetrics.from(List.of(
                run("only", true, 123, 777, 1, 0, 1, 0)));
        assertEquals(777, row.latencyP50Ms());
        assertEquals(777, row.latencyP95Ms());
        assertEquals(777, row.latencyP99Ms());
        assertEquals(123, row.costPerTaskMicros());
        assertTrue(row.memoryHitRate() == null);
    }

    // ============ Helpers ============

    private static RunMetrics run(String runId, boolean done, long costMicros, long durationMs,
                                  int modelCalls, int modelErrors, int toolCalls, int deniedTools) {
        return new RunMetrics(
                runId, "support",
                done ? AgentState.Status.DONE : AgentState.Status.ERROR,
                done ? null : "boom",
                durationMs,
                modelCalls, modelErrors, toolCalls, deniedTools,
                new ModelResponse.TokenUsage(100, 40, 140, 0),
                costMicros);
    }
}

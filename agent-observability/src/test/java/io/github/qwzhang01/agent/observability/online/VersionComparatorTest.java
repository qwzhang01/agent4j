package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * acceptance for version comparison: live vs shadow deltas under
 * identical metric definitions, and combination-grouped windows.
 */
class VersionComparatorTest {

    @Test
    void deltasAreDirectionalCandidateMinusLive() {
        OnlineMetrics live = new OnlineMetrics(10, 8, 0.8, 1000, 500, 900, 950, 0.01, 0.02, null);
        OnlineMetrics shadow = new OnlineMetrics(10, 9, 0.9, 800, 400, 700, 750, 0.01, 0.04, null);

        VersionComparator.Comparison c = VersionComparator.compareRows(live, shadow);

        assertEquals(0.1, c.completionRateDelta(), 1e-9, "candidate completes MORE");
        assertEquals(-200, c.costPerTaskDeltaMicros(), "candidate is CHEAPER");
        assertEquals(-100, c.latencyP50DeltaMs());
        assertEquals(-200, c.latencyP95DeltaMs());
        assertEquals(0.0, c.safetyViolationRateDelta(), 1e-9);
        assertEquals(0.02, c.fallbackRateDelta(), 1e-9, "candidate falls back MORE (a cost of cheapness)");
        assertTrue(c.summary().contains("completion +10.0%"), c.summary());
    }

    @Test
    void comparesRealWindowsAndGroupsByCombination() {
        List<RunMetrics> liveRows = List.of(
                row("r1", true, 1000, 500),
                row("r2", false, 1000, 500));
        List<RunMetrics> shadowRows = List.of(
                row("s1", true, 500, 200),
                row("s2", true, 500, 200));

        VersionComparator.Comparison c = VersionComparator.compare(liveRows, shadowRows);
        assertEquals(0.5, c.completionRateDelta(), 1e-9);   // 1.0 - 0.5
        assertEquals(-500, c.costPerTaskDeltaMicros());     // 500 - 1000

        Map<String, OnlineMetrics> grouped = VersionComparator.byCombination(Map.of(
                "PROMPT v3, MODEL premium", liveRows,
                "PROMPT v4, MODEL premium", shadowRows));
        assertEquals(2, grouped.size());
        assertEquals(0.5, grouped.get("PROMPT v3, MODEL premium").taskCompletionRate(), 1e-9);
        assertEquals(1.0, grouped.get("PROMPT v4, MODEL premium").taskCompletionRate(), 1e-9);
    }

    @Test
    void compareByKeyReturnsEmptyWhenEitherSideIsMissing() {
        List<RunMetrics> rows = List.of(row("r1", true, 100, 100));
        Map<String, List<RunMetrics>> groups = Map.of("live", rows);

        assertTrue(VersionComparator.compareByKey(groups, "live", "absent").isEmpty());
        assertTrue(VersionComparator.compareByKey(groups, "absent", "live").isEmpty());
        assertTrue(VersionComparator.compareByKey(groups, "live", "live").isPresent());
    }

    @Test
    void byCombinationSkipsEmptyGroupsHonestly() {
        List<RunMetrics> rows = List.of(row("r1", true, 100, 100));
        Map<String, OnlineMetrics> grouped = VersionComparator.byCombination(Map.of(
                "real", rows,
                "empty", List.of()));
        assertEquals(1, grouped.size(), "an empty group is no group");
    }

    @Test
    void forAgentFiltersRows() {
        List<RunMetrics> rows = List.of(
                row("r1", true, 100, 100),
                new RunMetrics("r2", "other-agent", AgentState.Status.DONE, null, 100,
                        1, 0, 0, 0, new ModelResponse.TokenUsage(1, 1, 2, 0), 0));
        assertEquals(1, VersionComparator.forAgent(rows, "support").size());
    }

    private static RunMetrics row(String runId, boolean done, long cost, long duration) {
        return new RunMetrics(runId, "support",
                done ? AgentState.Status.DONE : AgentState.Status.ERROR,
                done ? null : "boom", duration,
                1, 0, 0, 0, new ModelResponse.TokenUsage(1, 1, 2, 0), cost);
    }
}

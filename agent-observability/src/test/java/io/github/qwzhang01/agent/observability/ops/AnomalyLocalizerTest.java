package io.github.qwzhang01.agent.observability.ops;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.version.ComponentVersion;
import io.github.qwzhang01.agent.observability.version.RunRecord;
import io.github.qwzhang01.agent.observability.version.RunRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.4 acceptance for anomaly localization: a drifted window walks
 * down to version combinations, denied tools and provider errors, ranked
 * by contribution.
 */
class AnomalyLocalizerTest {

    @Test
    void healthyWindowHasNoSuspects() {
        List<RunMetrics> rows = List.of(done("r1"), done("r2"));
        assertTrue(AnomalyLocalizer.localize(rows, new RunRegistry()).isEmpty());
    }

    @Test
    void failedRunsAreLocalizedToTheirVersionCombinationsRankedByContribution() {
        RunRegistry registry = new RunRegistry();
        registry.add(record("f1", "PROMPT v3", 0, 0));
        registry.add(record("f2", "PROMPT v3", 0, 0));
        registry.add(record("f3", "PROMPT v4", 0, 0));

        List<RunMetrics> window = List.of(done("ok1"), failed("f1"), failed("f2"), failed("f3"));
        List<AnomalyLocalizer.Suspect> suspects = AnomalyLocalizer.localize(window, registry);

        assertEquals(2, suspects.size());
        // v3 served 2 failed runs, v4 served 1: v3 ranks first
        assertEquals("version", suspects.get(0).dimension());
        assertEquals(2, suspects.get(0).failedRuns());
        assertTrue(suspects.get(0).identity().contains("PROMPT support-system@v3"));
        assertEquals(1, suspects.get(1).failedRuns());
    }

    @Test
    void deniedToolsAndModelErrorsBecomeSuspectsToo() {
        List<RunMetrics> window = List.of(
                failedWithCounts("f1", 3, 5),   // 3 model errors, 5 denied tools
                failedWithCounts("f2", 1, 2));

        List<AnomalyLocalizer.Suspect> suspects = AnomalyLocalizer.localize(window, null);

        assertEquals(2, suspects.size());
        // denied tools (7 total) out-contribute model errors (4 total)
        assertEquals("tool", suspects.get(0).dimension());
        assertEquals(7, suspects.get(0).failedRuns());
        assertEquals("provider", suspects.get(1).dimension());
        assertEquals(4, suspects.get(1).failedRuns());
    }

    @Test
    void unrecordedRunsAreNamedHonestlyNotSkipped() {
        RunRegistry registry = new RunRegistry();  // empty: nothing recorded
        List<RunMetrics> window = List.of(failedWithCounts("mystery", 0, 0));

        List<AnomalyLocalizer.Suspect> suspects = AnomalyLocalizer.localize(window, registry);

        assertEquals(1, suspects.size());
        assertEquals("(no version recorded)", suspects.get(0).identity(),
                "the absence of a version record is itself a finding");
    }

    private static RunMetrics done(String runId) {
        return new RunMetrics(runId, "support", AgentState.Status.DONE, null, 100,
                1, 0, 0, 0, new ModelResponse.TokenUsage(1, 1, 2, 0), 0);
    }

    private static RunMetrics failed(String runId) {
        return failedWithCounts(runId, 0, 0);
    }

    private static RunMetrics failedWithCounts(String runId, int modelErrors, int denied) {
        return new RunMetrics(runId, "support", AgentState.Status.ERROR, "boom", 100,
                2, modelErrors, 3, denied, new ModelResponse.TokenUsage(1, 1, 2, 0), 0);
    }

    private static RunRecord record(String runId, String promptVersion, int modelErrors, int denied) {
        return new RunRecord(runId, "support",
                List.of(ComponentVersion.of(ComponentVersion.Kind.PROMPT, "support-system",
                        promptVersion.startsWith("PROMPT v3") ? "v3" : "v4")),
                failedWithCounts(runId, modelErrors, denied));
    }
}

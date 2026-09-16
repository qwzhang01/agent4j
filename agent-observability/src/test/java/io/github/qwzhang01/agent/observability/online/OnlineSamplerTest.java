package io.github.qwzhang01.agent.observability.online;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.3 acceptance for the online sampler: deterministic 1-in-N
 * sampling, failures ALWAYS kept (survivor-bias guard), bounded window.
 */
class OnlineSamplerTest {

    @Test
    void oneInOneKeepsEverythingUpToTheBound() {
        OnlineSampler sampler = new OnlineSampler(1, 3);
        sampler.offer(done("r1"));
        sampler.offer(done("r2"));
        sampler.offer(done("r3"));
        sampler.offer(done("r4"));  // evicts r1

        assertEquals(4, sampler.offered());
        assertEquals(3, sampler.window().size(), "bounded ring");
        assertEquals(List.of("r2", "r3", "r4"),
                sampler.window().stream().map(RunMetrics::runId).toList(),
                "oldest evicted first");
    }

    @Test
    void failuresAreAlwaysKeptDespiteSampling() {
        OnlineSampler sampler = new OnlineSampler(1000, 100);  // keep ~nothing
        sampler.offer(failed("f1"));
        sampler.offer(failed("f2"));

        assertEquals(2, sampler.keptFailures());
        assertEquals(2, sampler.window().size(),
                "failures are the scarce signal: sampling must not drop them");
    }

    @Test
    void samplingIsDeterministicPerRunId() {
        OnlineSampler sampler = new OnlineSampler(2, 100);
        // same runId offered twice lands the same verdict (hash-based, no RNG)
        int keptBefore = sampler.window().size();
        sampler.offer(done("deterministic-id"));
        int keptAfterOne = sampler.window().size();
        sampler.offer(done("deterministic-id"));
        int keptAfterTwo = sampler.window().size();

        // whatever the verdict for this id, it is the SAME both times
        assertEquals(keptAfterTwo - keptAfterOne, keptAfterOne - keptBefore,
                "same runId -> same keep/drop verdict");
    }

    @Test
    void snapshotAggregatesTheCurrentWindow() {
        OnlineSampler sampler = new OnlineSampler(1, 100);
        sampler.offer(done("r1"));
        sampler.offer(failed("f1"));

        OnlineMetrics snapshot = sampler.snapshot().orElseThrow();
        assertEquals(2, snapshot.runs());
        assertEquals(0.5, snapshot.taskCompletionRate(), 1e-9);
    }

    @Test
    void emptyWindowSnapshotIsHonestEmpty() {
        OnlineSampler sampler = new OnlineSampler(10, 10);
        assertTrue(sampler.snapshot().isEmpty(), "no rows yet: no measurement yet");
    }

    @Test
    void constructorRejectsNonPositiveParameters() {
        assertThrows(IllegalArgumentException.class, () -> new OnlineSampler(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new OnlineSampler(1, 0));
    }

    // ============ Helpers ============

    private static RunMetrics done(String runId) {
        return new RunMetrics(runId, "support", AgentState.Status.DONE, null, 100,
                1, 0, 0, 0, new ModelResponse.TokenUsage(10, 5, 15, 0), 50);
    }

    private static RunMetrics failed(String runId) {
        return new RunMetrics(runId, "support", AgentState.Status.ERROR, "boom", 100,
                1, 1, 0, 0, new ModelResponse.TokenUsage(10, 5, 15, 0), 50);
    }
}

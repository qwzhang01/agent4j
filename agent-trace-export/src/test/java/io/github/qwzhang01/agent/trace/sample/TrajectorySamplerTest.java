package io.github.qwzhang01.agent.trace.sample;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sampling determinism and filters (M14.2 verification: same seed -> same
 * decisions, rate 0/100 boundaries, status/step/reward filters, failed
 * trajectories kept by default - negative samples are assets, D4).
 */
class TrajectorySamplerTest {

    @Test
    void rateZeroRejectsAllRate100AcceptsAll() {
        var success = TrajectoryFixture.successful("run-1");
        assertTrue(new TrajectorySampler(SamplingPolicy.rate(100, 7L)).shouldExport(success));
        assertFalse(new TrajectorySampler(SamplingPolicy.rate(0, 7L)).shouldExport(success));
    }

    @Test
    void sameSeedAlwaysDecidesIdentically() {
        var sampler = new TrajectorySampler(SamplingPolicy.rate(50, 42L));
        List<Boolean> first = new ArrayList<>();
        List<Boolean> second = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            var trajectory = TrajectoryFixture.successful("run-" + i);
            first.add(sampler.shouldExport(trajectory));
            second.add(sampler.shouldExport(trajectory));
        }
        assertEquals(first, second);
    }

    @Test
    void rateFiftyIsRoughlySpread() {
        var sampler = new TrajectorySampler(SamplingPolicy.rate(50, 42L));
        long kept = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> TrajectoryFixture.successful("run-" + i))
                .filter(sampler::shouldExport)
                .count();
        // guard against degenerate hashing (all-keep / all-drop bugs)
        assertTrue(kept >= 20 && kept <= 80, "kept=" + kept);
    }

    @Test
    void statusFilterKeepsFailedByDefaultOnlyWhenConfigured() {
        var failure = TrajectoryFixture.failed("run-err");
        // default: keep everything including ERROR (negative-sample assets)
        assertTrue(new TrajectorySampler(SamplingPolicy.all()).shouldExport(failure));
        // explicit DONE-only filter rejects it
        var doneOnly = new TrajectorySampler(
                new SamplingPolicy(100, 0L, Set.of(AgentState.Status.DONE), null, null, null));
        assertFalse(doneOnly.shouldExport(failure));
        assertTrue(doneOnly.shouldExport(TrajectoryFixture.successful("run-ok")));
    }

    @Test
    void stepBoundsFilter() {
        // success has 3 steps, failure has 1 step
        var exactBand = new TrajectorySampler(new SamplingPolicy(100, 0L, Set.of(), 2, 2, null));
        assertFalse(exactBand.shouldExport(TrajectoryFixture.successful("r1")));
        assertFalse(exactBand.shouldExport(TrajectoryFixture.failed("r2")));

        var wideBand = new TrajectorySampler(new SamplingPolicy(100, 0L, Set.of(), 1, 3, null));
        assertTrue(wideBand.shouldExport(TrajectoryFixture.successful("r3")));
        assertTrue(wideBand.shouldExport(TrajectoryFixture.failed("r4")));
    }

    @Test
    void rewardThresholdFilter() {
        var policy = new SamplingPolicy(100, 0L, Set.of(), null, null, 0.5);
        var sampler = new TrajectorySampler(policy);
        assertTrue(sampler.shouldExport(TrajectoryFixture.withReward(TrajectoryFixture.successful("r1"), 1.0)));
        assertFalse(sampler.shouldExport(TrajectoryFixture.withReward(TrajectoryFixture.successful("r2"), 0.2)));
        // fail-closed: unscored trajectories cannot pass a reward threshold
        assertFalse(sampler.shouldExport(TrajectoryFixture.successful("r3")));
    }

    @Test
    void invalidRateRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SamplingPolicy(101, 0L, Set.of(), null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SamplingPolicy(-1, 0L, Set.of(), null, null, null));
    }
}

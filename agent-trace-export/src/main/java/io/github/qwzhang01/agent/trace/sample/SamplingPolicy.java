package io.github.qwzhang01.agent.trace.sample;

import io.github.qwzhang01.agent.core.agent.AgentState;

import java.util.Set;

/**
 * WHICH runs are worth persisting (Stage 14 D4). Recording is always-on and
 * cheap (in-memory append); sampling is the AFTER-THE-FACT storage decision,
 * because how good a run was is only known when it ends.
 * <p>
 * Defaults are deliberately permissive: {@link #all()} keeps everything
 * including ERROR trajectories - failed runs are negative-sample assets
 * (the rejected half of DPO pairs). Filtering is explicit configuration,
 * never silent default.
 *
 * @param sampleRate percent of runs to keep, 0-100 (hash-based, deterministic)
 * @param seed       sampling seed; the same (runId, seed) pair always decides
 *                   the same way - auditable, reproducible, no Random
 * @param statuses   terminal statuses to keep; empty/null = keep all
 * @param minSteps   minimum step count to keep; null = no lower bound
 * @param maxSteps   maximum step count to keep; null = no upper bound
 * @param minReward  minimum outcome reward to keep; null = no bound. When
 *                   set, unscored trajectories (reward == null) are REJECTED
 *                   (fail-closed: no reward, no threshold check)
 */
public record SamplingPolicy(
        int sampleRate,
        long seed,
        Set<AgentState.Status> statuses,
        Integer minSteps,
        Integer maxSteps,
        Double minReward
) {
    public SamplingPolicy {
        if (sampleRate < 0 || sampleRate > 100) {
            throw new IllegalArgumentException("sampleRate must be 0-100, got " + sampleRate);
        }
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
    }

    /** Keep everything (rate 100, no filters) - the honest default. */
    public static SamplingPolicy all() {
        return new SamplingPolicy(100, 0L, Set.of(), null, null, null);
    }

    /** Deterministic rate sampling with explicit seed (0-100). */
    public static SamplingPolicy rate(int percent, long seed) {
        return new SamplingPolicy(percent, seed, Set.of(), null, null, null);
    }
}

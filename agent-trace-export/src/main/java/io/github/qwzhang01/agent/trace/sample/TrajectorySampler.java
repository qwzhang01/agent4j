package io.github.qwzhang01.agent.trace.sample;

import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

/**
 * Applies a {@link SamplingPolicy} to finished trajectories (Stage 14 D4).
 * <p>
 * Rate decisions are deterministic: {@code floorMod(runId.hashCode() ^ seed, 100) < rate}.
 * String.hashCode is JLS-mandated, so the same runId+seed decides identically
 * across JVMs and reruns - "why wasn't this exported" is always answerable by
 * recomputing the hash, never by chasing a Random seed.
 */
public final class TrajectorySampler {

    private final SamplingPolicy policy;

    public TrajectorySampler(SamplingPolicy policy) {
        this.policy = policy;
    }

    public SamplingPolicy policy() {
        return policy;
    }

    /**
     * Should this trajectory be persisted? All filters pass-through style:
     * cheap structural checks first, rate last.
     */
    public boolean shouldExport(Trajectory trajectory) {
        if (!policy.statuses().isEmpty() && !policy.statuses().contains(trajectory.status())) {
            return false;
        }
        int steps = trajectory.steps().size();
        if (policy.minSteps() != null && steps < policy.minSteps()) {
            return false;
        }
        if (policy.maxSteps() != null && steps > policy.maxSteps()) {
            return false;
        }
        if (policy.minReward() != null) {
            // fail-closed: without a reward there is nothing to threshold on
            if (trajectory.reward() == null || trajectory.reward() < policy.minReward()) {
                return false;
            }
        }
        return passesRate(trajectory.runId());
    }

    private boolean passesRate(String runId) {
        int bucket = Math.floorMod(runId.hashCode() ^ (int) policy.seed(), 100);
        return bucket < policy.sampleRate();
    }
}

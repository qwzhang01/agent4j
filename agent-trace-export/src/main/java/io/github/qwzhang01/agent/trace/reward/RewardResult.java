package io.github.qwzhang01.agent.trace.reward;

import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

/**
 * The outcome of scoring one trajectory (Stage 14 D5).
 *
 * @param reward     the reward value (sign is the only contract v1: positive good, negative bad)
 * @param source     where it came from: "rule" / "human" / future "model"
 * @param explanation optional human-readable justification (null normalized to "")
 */
public record RewardResult(double reward, String source, String explanation) {

    public RewardResult {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("reward source must not be blank");
        }
        explanation = explanation == null ? "" : explanation;
    }

    public static RewardResult of(double reward, String source) {
        return new RewardResult(reward, source, null);
    }

    /**
     * Attach this result to a trajectory (immutable wither): returns a new
     * {@link Trajectory} carrying reward/rewardSource; the original is
     * untouched (record immutability, same discipline as Stage 6 Checkpoint).
     */
    public Trajectory applyTo(Trajectory trajectory) {
        return new Trajectory(trajectory.trajectoryId(), trajectory.runId(),
                trajectory.metadata(), trajectory.status(), trajectory.steps(),
                trajectory.messages(), reward, source);
    }
}

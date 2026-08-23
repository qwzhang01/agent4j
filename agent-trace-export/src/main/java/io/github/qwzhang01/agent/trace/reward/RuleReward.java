package io.github.qwzhang01.agent.trace.reward;

import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.util.EnumMap;
import java.util.Map;

/**
 * Rule-based reward (Stage 14 D5, v1 default): maps the terminal DoneReason
 * to a score. "Did it finish" is the cheapest honest signal - anything
 * fancier (did it finish WELL) needs human feedback or a judge, which are
 * the other RewardSource slots.
 * <p>
 * Defaults: DONE +1.0 / MAX_STEPS_EXCEEDED -0.5 / ERROR -1.0 / CANCELLED 0.0.
 * The mapping is config, not law - "completed the task" means different
 * things to different agents, so {@link #withReward} returns a customized
 * copy instead of mutating.
 */
public final class RuleReward implements RewardSource {

    private final Map<DoneReason, Double> rewards;

    private RuleReward(Map<DoneReason, Double> rewards) {
        this.rewards = rewards;
    }

    public static RuleReward defaults() {
        return new RuleReward(Map.of(
                DoneReason.DONE, 1.0,
                DoneReason.MAX_STEPS_EXCEEDED, -0.5,
                DoneReason.ERROR, -1.0,
                DoneReason.CANCELLED, 0.0));
    }

    /**
     * Return a copy with one reason's score overridden (immutable).
     */
    public RuleReward withReward(DoneReason reason, double value) {
        var next = new EnumMap<DoneReason, Double>(this.rewards);
        next.put(reason, value);
        return new RuleReward(Map.copyOf(next));
    }

    @Override
    public RewardResult score(Trajectory trajectory) {
        DoneReason reason = trajectory.doneReason();
        if (reason == null) {
            // no model call ever happened (empty steps) - no signal, no invention
            return new RewardResult(0.0, "rule", "no steps recorded");
        }
        Double value = rewards.get(reason);
        if (value == null) {
            return new RewardResult(0.0, "rule", "no rule for " + reason);
        }
        return new RewardResult(value, "rule", reason.name().toLowerCase().replace('_', ' '));
    }
}

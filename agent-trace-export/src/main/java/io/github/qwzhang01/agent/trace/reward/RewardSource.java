package io.github.qwzhang01.agent.trace.reward;

import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

/**
 * Pluggable reward source (Stage 14 D5): how good was this run?
 * <p>
 * Three implementation slots by design:
 * <ul>
 *   <li>{@link RuleReward} (v1) - terminal-status mapping; "did it finish" is
 *       the cheapest honest signal</li>
 *   <li>Human feedback (M14.4) - annotation sidecar joined by trajectoryId;
 *       exported data is append-only, annotations never rewrite trajectories</li>
 *   <li>LLM-as-judge (v2) - extension point, interface already in place</li>
 * </ul>
 * Step-level rewards are NOT this interface's business: v1 keeps them null
 * (fabricating process rewards would poison training data - blueprint D5).
 */
public interface RewardSource {

    /**
     * Score one trajectory. Implementations must be deterministic and
     * side-effect free: the same trajectory always scores the same.
     */
    RewardResult score(Trajectory trajectory);
}

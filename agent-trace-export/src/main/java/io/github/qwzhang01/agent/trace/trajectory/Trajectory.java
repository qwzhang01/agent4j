package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * One complete run as RL training data (Stage 14): the in-memory form of what
 * M14.2 will serialize to the versioned JSONL contract.
 * <p>
 * Two consumption channels (D3):
 * <ul>
 *   <li>{@code messages} - the LOGICAL full conversation
 *       (system, user, assistant(toolCalls), tool, ..., assistant). This is
 *       what SFT/DPO trainers consume. Assembled from step 1's leading state
 *       plus every action and observation - NOT from AgentState, so it stays
 *       consistent with what was recorded at the boundaries.</li>
 *   <li>{@code steps} - per-model-call State/Action/Observation/Reward/Done
 *       structures for process analysis and replay. Under compression the
 *       per-step states show trimmed windows while {@code messages} keeps the
 *       logical flow - both views are true, they answer different questions.</li>
 * </ul>
 * {@code reward} / {@code rewardSource} are null until a RewardSource scores
 * this trajectory (M14.2); the recorder never invents values (D5).
 *
 * @param trajectoryId unique trajectory id (UUID)
 * @param runId        run id (caller-supplied or recorder-generated)
 * @param metadata     run metadata (config fingerprint, timings, token cost)
 * @param status       loop terminal status (reuses AgentState.Status - one vocabulary, Stage 12 lesson)
 * @param steps        one step per model call
 * @param messages     logical full conversation
 * @param reward       outcome reward, null until scored (M14.2)
 * @param rewardSource where the reward came from ("rule"/"human"/...), null until scored
 */
public record Trajectory(
        String trajectoryId,
        String runId,
        TrajectoryMetadata metadata,
        AgentState.Status status,
        List<TrajectoryStep> steps,
        List<ChatMessage> messages,
        Double reward,
        String rewardSource
) {
    public Trajectory {
        steps = List.copyOf(steps);
        messages = List.copyOf(messages);
    }

    /**
     * Convenience view on the terminal reason of the last step
     * (null for empty trajectories).
     */
    public DoneReason doneReason() {
        return steps.isEmpty() ? null : steps.get(steps.size() - 1).doneReason();
    }
}

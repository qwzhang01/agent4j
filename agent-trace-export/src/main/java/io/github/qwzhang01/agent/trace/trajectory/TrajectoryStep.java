package io.github.qwzhang01.agent.trace.trajectory;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * One step of a trajectory = one model call (Stage 14 D3).
 * <p>
 * State / Action / Observation / Reward / Done:
 * <ul>
 *   <li>{@code state} - the EXACT messages of this model request,
 *       post-ContextBuilder (D1). Under compression/windowing this differs
 *       from {@code AgentState.getMessages()} - that divergence is the point:
 *       the policy's real input is what the model saw, not the full history.</li>
 *   <li>{@code action} - the model's response on this call.</li>
 *   <li>{@code observations} - tool results executed after this response
 *       (all of them - parallel tool calls of one response belong to one step).</li>
 *   <li>{@code reward} - step-level reward. v1 is ALWAYS null (outcome reward
 *       lives on Trajectory; process reward would be fabricated data - D5).</li>
 *   <li>{@code done} / {@code doneReason} - terminal marker; only the last
 *       step of a run carries done=true.</li>
 * </ul>
 * The state is a full snapshot per step (not a delta): exactness for ANY
 * ContextBuilder beats O(n) storage for v1 step counts (default max 10).
 * Delta encoding, if needed, is an export-time concern (M14.2).
 *
 * @param index        1-based step index
 * @param state        exact request messages of this call (post-ContextBuilder)
 * @param action       the model's response
 * @param observations tool results after this response (empty for final answer)
 * @param reward       step reward - null in v1 (never fabricated)
 * @param done         true only for the terminal step
 * @param doneReason   terminal reason, null unless done
 */
public record TrajectoryStep(
        int index,
        List<ChatMessage> state,
        StepAction action,
        List<ToolObservation> observations,
        Double reward,
        boolean done,
        DoneReason doneReason
) {
    public TrajectoryStep {
        state = List.copyOf(state);
        observations = List.copyOf(observations);
    }
}

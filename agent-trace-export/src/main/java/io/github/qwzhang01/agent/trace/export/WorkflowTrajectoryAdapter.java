package io.github.qwzhang01.agent.trace.export;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fulfills the promise written in {@link StepRecord}'s javadoc since Stage 5:
 * "Stage 14 (RL trajectory export) consumes these records directly."
 * <p>
 * HONEST granularity: a workflow run has no model calls, so this is a
 * NODE-LEVEL projection, not a per-model-call trajectory:
 * <ul>
 *   <li>one TrajectoryStep per StepRecord; step "state" is the blackboard
 *       view the node could see (workflow header + input + prior node
 *       summaries), NOT a post-ContextBuilder model input</li>
 *   <li>action content = node summary, finishReason = node status name,
 *       no token usage (none exists at this level)</li>
 *   <li>terminal mapping: SUCCEEDED -&gt; DONE, FAILED -&gt; ERROR (lastError =
 *       errorMessage), CANCELLED -&gt; doneReason CANCELLED with status ERROR
 *       (AgentState.Status has no CANCELLED - the loop vocabulary is reused,
 *       the semantic lives in doneReason); PAUSED is rejected: a paused run
 *       is not a finished trajectory, resume it first (cross-resume splice
 *       is v2, blueprint §12)</li>
 * </ul>
 * The payoff of mapping into the Trajectory model: reward, sampling, the
 * JSONL contract and replay all work on workflow runs unchanged.
 */
public final class WorkflowTrajectoryAdapter {

    private WorkflowTrajectoryAdapter() {
    }

    /** Adapt with an explicit runId (from RunManager/RunState). */
    public static Trajectory adapt(String runId, WorkflowState state, ExecutionResult result) {
        if (result.status() == ExecutionResult.Status.PAUSED) {
            throw new IllegalArgumentException(
                    "a PAUSED run is not a finished trajectory - resume it before adapting (splice is v2)");
        }
        String id = runId != null ? runId : "wf-run-" + UUID.randomUUID();

        DoneReason doneReason = switch (result.status()) {
            case SUCCEEDED -> DoneReason.DONE;
            case FAILED -> DoneReason.ERROR;
            case CANCELLED -> DoneReason.CANCELLED;
            case PAUSED -> throw new IllegalStateException("unreachable"); // rejected above
        };
        AgentState.Status status = result.status() == ExecutionResult.Status.SUCCEEDED
                ? AgentState.Status.DONE
                : AgentState.Status.ERROR;

        List<StepRecord> trace = result.trace();
        List<ChatMessage> header = List.of(
                ChatMessage.system("workflow-run (nodes: " + trace.size() + ")"),
                ChatMessage.user(String.valueOf(state == null ? null : state.getInput())));

        List<TrajectoryStep> steps = new ArrayList<>();
        List<ChatMessage> blackboard = new ArrayList<>(header);
        for (int i = 0; i < trace.size(); i++) {
            StepRecord record = trace.get(i);
            boolean last = i == trace.size() - 1;
            steps.add(new TrajectoryStep(
                    i + 1,
                    List.copyOf(blackboard),
                    new StepAction(record.summary(), null, record.status().name(), null, record.durationMs()),
                    List.of(),
                    null,
                    last,
                    last ? doneReason : null));
            // later nodes see this node's summary on the blackboard
            blackboard.add(ChatMessage.tool(record.nodeId(), record.nodeId(), record.summary()));
        }

        long totalDurationMs = trace.stream().mapToLong(StepRecord::durationMs).sum();
        String lastError = result.status() == ExecutionResult.Status.SUCCEEDED ? null : result.errorMessage();
        var metadata = new TrajectoryMetadata("workflow", null, List.of(), null,
                null, null, totalDurationMs, null, lastError, Map.of());

        // messages channel == TrajectorySteps reconstruction by construction
        List<ChatMessage> messages = io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps
                .logicalMessages(steps);
        return new Trajectory("traj-wf-" + UUID.randomUUID(), id, metadata, status, steps, messages,
                null, null);
    }

    /** Adapt with an auto-generated workflow runId. */
    public static Trajectory adapt(WorkflowState state, ExecutionResult result) {
        return adapt(null, state, result);
    }
}

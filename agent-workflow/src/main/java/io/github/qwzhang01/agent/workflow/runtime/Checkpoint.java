package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.workflow.WorkflowState;

/**
 * A serializable snapshot of a Run at a point in time.
 * <p>
 * Design decision (D1): stores the blackboard (WorkflowState) and cursor,
 * NOT the Workflow definition itself (it's immutable and re-loadable by name).
 * <p>
 * Design decision (D2): cursor = the node to re-execute on resume.
 * Completed nodes are NOT re-executed because their outputs are already
 * in the blackboard's variables zone, and the cursor starts past them.
 *
 * @param checkpointId    unique id of this snapshot
 * @param runId           the Run this checkpoint belongs to
 * @param status          RunState at checkpoint time (usually PAUSED)
 * @param cursor          next node to execute on resume (null = from START)
 * @param state           the complete blackboard snapshot
 * @param timestamp       when this checkpoint was created
 * @param stepsExecuted   total steps so far (for maxSteps across pause/resume)
 * @param pendingInput    input for the paused node on resume (its original input)
 */
public record Checkpoint(
        String checkpointId,
        String runId,
        RunState status,
        String cursor,
        WorkflowState state,
        long timestamp,
        int stepsExecuted,
        Object pendingInput
) {
    public static Checkpoint of(Run run) {
        return new Checkpoint(
                java.util.UUID.randomUUID().toString(),
                run.getRunId(),
                run.getStatus(),
                run.getCursor(),
                run.getState(),
                System.currentTimeMillis(),
                run.getStepsExecuted(),
                run.getPendingInput()
        );
    }
}

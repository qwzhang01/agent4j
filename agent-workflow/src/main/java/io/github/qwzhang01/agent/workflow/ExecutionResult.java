package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.ResumeToken;
import io.github.qwzhang01.agent.workflow.runtime.RunState;

import java.util.List;

/**
 * Final outcome of one workflow run attempt.
 * <p>
 * The runtime never throws for run-level problems; it returns FAILED
 * with an errorMessage (same philosophy as AgentState.Status.ERROR).
 * Definition bugs throw at build time instead.
 * <p>
 * Stage 6 additions: PAUSED and CANCELLED statuses.
 * - PAUSED: a node threw PauseException; resumeToken is non-null
 * - CANCELLED: caller requested cancellation
 *
 * @param status       SUCCEEDED / FAILED / PAUSED / CANCELLED
 * @param output       output of the last executed node (null when not SUCCEEDED)
 * @param errorMessage failure reason (null unless FAILED)
 * @param state        the blackboard, including the step trace
 * @param resumeToken  present only when PAUSED (null otherwise)
 */
public record ExecutionResult(Status status, Object output, String errorMessage,
                               WorkflowState state, ResumeToken resumeToken) {

    public enum Status {
        SUCCEEDED, FAILED, PAUSED, CANCELLED;

        public boolean isTerminal() {
            return this != PAUSED;
        }
    }

    public static ExecutionResult success(Object output, WorkflowState state) {
        return new ExecutionResult(Status.SUCCEEDED, output, null, state, null);
    }

    public static ExecutionResult failed(String errorMessage, WorkflowState state) {
        return new ExecutionResult(Status.FAILED, null, errorMessage, state, null);
    }

    public static ExecutionResult paused(ResumeToken token, WorkflowState state) {
        return new ExecutionResult(Status.PAUSED, null, null, state, token);
    }

    public static ExecutionResult cancelled(WorkflowState state) {
        return new ExecutionResult(Status.CANCELLED, null, "Run cancelled", state, null);
    }

    public boolean isSucceeded() {
        return status == Status.SUCCEEDED;
    }

    public boolean isPaused() {
        return status == Status.PAUSED;
    }

    public boolean isCancelled() {
        return status == Status.CANCELLED;
    }

    /**
     * Step trace of this run (delegates to the blackboard).
     */
    public List<StepRecord> trace() {
        return state.getTrace();
    }
}

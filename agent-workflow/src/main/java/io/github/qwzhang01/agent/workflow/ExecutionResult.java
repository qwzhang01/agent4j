package io.github.qwzhang01.agent.workflow;

import java.util.List;

/**
 * Final outcome of one workflow run.
 * <p>
 * The runtime never throws for run-level problems; it returns FAILED
 * with an errorMessage (same philosophy as AgentState.Status.ERROR).
 * Definition bugs throw at build time instead.
 *
 * @param status       SUCCEEDED (reached END) or FAILED
 * @param output       output of the last executed node (null when FAILED)
 * @param errorMessage failure reason (null when SUCCEEDED)
 * @param state        the blackboard, including the step trace
 */
public record ExecutionResult(Status status, Object output, String errorMessage, WorkflowState state) {

    public enum Status { SUCCEEDED, FAILED }

    public static ExecutionResult success(Object output, WorkflowState state) {
        return new ExecutionResult(Status.SUCCEEDED, output, null, state);
    }

    public static ExecutionResult failed(String errorMessage, WorkflowState state) {
        return new ExecutionResult(Status.FAILED, null, errorMessage, state);
    }

    public boolean isSucceeded() {
        return status == Status.SUCCEEDED;
    }

    /** Step trace of this run (delegates to the blackboard). */
    public List<StepRecord> trace() {
        return state.getTrace();
    }
}

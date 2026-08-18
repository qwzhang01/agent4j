package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowState;

/**
 * Handle to a single workflow execution.
 * <p>
 * Carries everything GraphRuntime needs to execute (or resume) a run:
 * - runId: unique identifier
 * - workflow: the immutable graph definition
 * - state: the blackboard (mutable, shared)
 * - status: current lifecycle state
 * - cursor: next node to execute (null = fresh start from START)
 * - pendingInput: input for the paused node on resume
 * - stepsExecuted: total steps so far (for maxSteps across pause/resume)
 * - cancelled: volatile flag for cooperative cancellation
 * <p>
 * A Run is created by {@link RunManager#start} and is the unit of
 * pause/resume/cancel operations.
 */
public class Run {

    // ============ Identity ============
    private final String runId;
    private final Workflow workflow;

    // ============ Mutable State ============
    private WorkflowState state;
    private RunState status;
    private String cursor;           // null = fresh start, non-null = resume from here
    private Object pendingInput;     // input for the paused node on resume
    private int stepsExecuted;
    private String errorMessage;

    // ============ Control ============
    private volatile boolean cancelled = false;
    private final long startTime;

    // ============ Constructors ============

    /** Fresh start. */
    public Run(String runId, Workflow workflow, WorkflowState state) {
        this.runId = runId;
        this.workflow = workflow;
        this.state = state;
        this.status = RunState.RUNNING;
        this.cursor = null;
        this.startTime = System.currentTimeMillis();
    }

    /** Restore from checkpoint (for crash recovery). */
    public static Run fromCheckpoint(Checkpoint cp, Workflow workflow) {
        Run run = new Run(cp.runId(), workflow, cp.state());
        run.status = RunState.RUNNING;  // resuming -> RUNNING
        run.cursor = cp.cursor();
        run.pendingInput = cp.pendingInput();
        run.stepsExecuted = cp.stepsExecuted();
        return run;
    }

    // ============ Getters ============

    public String getRunId() { return runId; }
    public Workflow getWorkflow() { return workflow; }
    public WorkflowState getState() { return state; }
    public RunState getStatus() { return status; }
    public String getCursor() { return cursor; }
    public Object getPendingInput() { return pendingInput; }
    public int getStepsExecuted() { return stepsExecuted; }
    public String getErrorMessage() { return errorMessage; }

    // ============ Setters (used by GraphRuntime across packages) ============

    public void setState(WorkflowState state) { this.state = state; }
    public void setStatus(RunState status) { this.status = status; }
    public void setCursor(String cursor) { this.cursor = cursor; }
    public void setPendingInput(Object pendingInput) { this.pendingInput = pendingInput; }
    public void setStepsExecuted(int steps) { this.stepsExecuted = steps; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }

    // ============ Cancellation ============

    /** Request cancellation. The run will stop at the next node boundary. */
    public void cancel() {
        this.cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    // ============ Checkpoint ============

    public Checkpoint toCheckpoint() {
        return Checkpoint.of(this);
    }
}

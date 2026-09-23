package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.core.run.CancellationSource;
import io.github.qwzhang01.agent.core.run.RunContext;
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

    // Identity
    private final String runId;
    private final Workflow workflow;

    // Mutable State
    private WorkflowState state;
    // Stage 3 hardening: status/errorMessage are read cross-thread by
    // observers (scheduler timers, recovery sweeps, tests). volatile +
    // "write errorMessage before status" makes FAILED a publication point:
    // anyone who sees FAILED is guaranteed to see the failure reason.
    private volatile RunState status;
    private String cursor;           // null = fresh start, non-null = resume from here
    private Object pendingInput;     // input for the paused node on resume
    private int stepsExecuted;
    private volatile String errorMessage;
    /** Stage 3.1: event/checkpoint sequence anchor for resume consistency. */
    private long lastEventSeq;

    // Control
    private volatile boolean cancelled = false;
    private final long startTime;
    private TimeoutPolicy timeoutPolicy = TimeoutPolicy.none();

    // Stage 1 (harness roadmap)
    /** Unified run context; null = legacy path (no context bound). */
    private RunContext runContext;
    /** Backs the context's token so RunManager.cancel flips both. */
    private final CancellationSource cancellationSource = new CancellationSource();

    /** Fresh start. */
    public Run(String runId, Workflow workflow, WorkflowState state) {
        this.runId = runId;
        this.workflow = workflow;
        this.state = state;
        this.status = RunState.RUNNING;
        this.cursor = null;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Stage 1 (harness roadmap): fresh start bound to a unified
     * {@link RunContext}. The context's runId wins when present; a
     * {@link CancellationSource}-backed token in the context is honored by
     * {@link #cancel()} (single cancel path).
     */
    public Run(String runId, Workflow workflow, WorkflowState state, RunContext runContext) {
        this(runId, workflow, state);
        bindContext(runContext);
    }

    /** Bind (or rebind) the unified context. No-op on null. */
    public void bindContext(RunContext runContext) {
        this.runContext = runContext;
    }

    /** The bound unified context, null on the legacy path. */
    public RunContext getRunContext() {
        return runContext;
    }

    /** Restore from checkpoint (for crash recovery). */
    public static Run fromCheckpoint(Checkpoint cp, Workflow workflow) {
        Run run = new Run(cp.runId(), workflow, cp.state());
        run.status = RunState.RUNNING;  // resuming -> RUNNING
        run.cursor = cp.cursor();
        run.pendingInput = cp.pendingInput();
        run.stepsExecuted = cp.stepsExecuted();
        run.lastEventSeq = cp.lastEventSeq();
        return run;
    }

    public String getRunId() { return runId; }
    public Workflow getWorkflow() { return workflow; }
    public WorkflowState getState() { return state; }
    public RunState getStatus() { return status; }
    public String getCursor() { return cursor; }
    public Object getPendingInput() { return pendingInput; }
    public int getStepsExecuted() { return stepsExecuted; }
    public String getErrorMessage() { return errorMessage; }
    public long getStartTime() { return startTime; }
    public TimeoutPolicy getTimeoutPolicy() { return timeoutPolicy; }
    /** Stage 3.1: event position anchor carried across pause/resume. */
    public long getLastEventSeq() { return lastEventSeq; }
    /** Stage 3.1: bump the event position (called on checkpoint persist). */
    public void setLastEventSeq(long seq) { this.lastEventSeq = seq; }

    public void setTimeoutPolicy(TimeoutPolicy timeoutPolicy) {
        this.timeoutPolicy = timeoutPolicy == null ? TimeoutPolicy.none() : timeoutPolicy;
    }

    // Setters (used by GraphRuntime across packages)

    public void setState(WorkflowState state) { this.state = state; }
    public void setStatus(RunState status) { this.status = status; }
    public void setCursor(String cursor) { this.cursor = cursor; }
    public void setPendingInput(Object pendingInput) { this.pendingInput = pendingInput; }
    public void setStepsExecuted(int steps) { this.stepsExecuted = steps; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }

    // Cancellation

    /**
     * Request cancellation. The run will stop at the next node boundary.
     * Stage 1: also flips the unified {@link CancellationSource} so every
     * component holding the RunContext token observes the same cancel.
     */
    public void cancel() {
        this.cancelled = true;
        this.cancellationSource.cancel();
    }

    /** Whether cancellation was requested (flag or unified token). */
    public boolean isCancelled() {
        return cancelled || cancellationSource.isCancelled()
                || (runContext != null && runContext.cancellationToken() != null
                        && runContext.cancellationToken().isCancelled());
    }

    // Checkpoint

    public Checkpoint toCheckpoint() {
        return Checkpoint.of(this);
    }
}

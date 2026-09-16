package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.util.List;

/**
 * A serializable snapshot of a Run at a point in time.
 * <p>
 * Design decision (D1): stores the blackboard (WorkflowState) and cursor,
 * NOT the Workflow definition itself (it's immutable and re-loadable by name).
 * <p>
 * Design decision (D2): cursor = the node to re-execute on resume.
 * Completed nodes are NOT re-executed because their outputs are already
 * in the blackboard's variables zone, and the cursor starts past them.
 * <p>
 * Stage 3.1 (harness roadmap) additions:
 * <ul>
 *   <li>{@code schemaVersion} — checkpoint format version, so a future
 *       format change is detectable instead of a silent misparse.</li>
 *   <li>{@code workflowVersion}/{@code workflowHash} — the definition
 *       identity the run started under; a resume against a changed
 *       definition is refused with {@code DEFINITION_VERSION_MISMATCH},
 *       never silently resumed.</li>
 *   <li>{@code lastEventSeq} — the event/checkpoint sequence anchor, tying
 *       this checkpoint to the RunStore row's event position so a resume
 *       cannot re-apply consumed history.</li>
 *   <li>{@code trace} — the step history rides along (Stage 3.2:
 *       visitOrdinal/attempt/result summaries), so recovery diagnostics do
 *       not need a separate event store in the teaching v1.</li>
 * </ul>
 *
 * @param schemaVersion    checkpoint format version (see SCHEMA_VERSION)
 * @param checkpointId     unique id of this snapshot
 * @param runId            the Run this checkpoint belongs to
 * @param workflowName     Workflow.name() this run started under
 * @param workflowVersion  Workflow.version() this run started under ("" = legacy)
 * @param workflowHash     Workflow.fingerprint() this run started under ("" = legacy)
 * @param status           RunState at checkpoint time (usually PAUSED)
 * @param cursor           next node to execute on resume (null = from START)
 * @param state            the complete blackboard snapshot
 * @param timestamp        when this checkpoint was created
 * @param stepsExecuted    total steps so far (for maxSteps across pause/resume)
 * @param pendingInput     input for the paused node on resume (its original input)
 * @param lastEventSeq     last applied event/checkpoint sequence (0 = none)
 * @param trace            step history at snapshot time (may be empty)
 */
public record Checkpoint(
        int schemaVersion,
        String checkpointId,
        String runId,
        String workflowName,
        String workflowVersion,
        String workflowHash,
        RunState status,
        String cursor,
        WorkflowState state,
        long timestamp,
        int stepsExecuted,
        Object pendingInput,
        long lastEventSeq,
        List<StepRecord> trace
) {

    /** Current checkpoint format version. */
    public static final int SCHEMA_VERSION = 2;

    public Checkpoint {
        if (schemaVersion <= 0) {
            schemaVersion = SCHEMA_VERSION;
        }
        java.util.Objects.requireNonNull(runId, "runId must not be null");
        java.util.Objects.requireNonNull(status, "status must not be null");
        trace = trace == null ? List.of() : List.copyOf(trace);
        workflowName = workflowName == null ? "" : workflowName;
        workflowVersion = workflowVersion == null ? "" : workflowVersion;
        workflowHash = workflowHash == null ? "" : workflowHash;
    }

    /**
     * Capture a snapshot of a Run. Workflow identity is taken from the
     * run's live definition (D1: the definition is not serialized, only
     * its identity hash).
     */
    public static Checkpoint of(Run run) {
        var wf = run.getWorkflow();
        return new Checkpoint(
                SCHEMA_VERSION,
                java.util.UUID.randomUUID().toString(),
                run.getRunId(),
                wf.name(),
                wf.version(),
                wf.fingerprint(),
                run.getStatus(),
                run.getCursor(),
                run.getState(),
                System.currentTimeMillis(),
                run.getStepsExecuted(),
                run.getPendingInput(),
                run.getLastEventSeq(),
                run.getState().getTrace()
        );
    }

    /** The definition identity a resume must match (name+version+hash). */
    public String definitionIdentity() {
        return workflowName + "@" + workflowVersion + "(" + workflowHash + ")";
    }
}

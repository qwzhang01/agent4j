package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.GraphRuntime;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lifecycle manager for workflow Runs: start / pause / resume / cancel.
 * <p>
 * This is the Stage 6 entry point. It wraps {@link GraphRuntime} with
 * checkpoint persistence and run tracking.
 * <p>
 * Usage:
 * <pre>{@code
 * RunManager mgr = new RunManager();
 *
 * // Start -> runs until END, PAUSED, FAILED, or CANCELLED
 * ExecutionResult r1 = mgr.start(workflow, "user input");
 *
 * if (r1.isPaused()) {
 *     ResumeToken token = r1.resumeToken();
 *     // ... human approval happens ...
 *     ExecutionResult r2 = mgr.resume(token.runId());
 * }
 * }</pre>
 * <p>
 * For crash recovery (process restart), use {@link #resume(String, Workflow)}:
 * <pre>{@code
 * RunManager mgr = new RunManager(new FileCheckpointStore(checkpointDir));
 * ExecutionResult r = mgr.resume(runId, workflow);  // loads from disk
 * }</pre>
 */
public class RunManager {

    private static final Logger log = LoggerFactory.getLogger(RunManager.class);

    private GraphRuntime runtime;
    private final CheckpointStore store;
    private final Map<String, Run> activeRuns = new ConcurrentHashMap<>();

    // ============ Constructors ============

    /** Default: InMemory checkpoint store. */
    public RunManager() {
        this(new GraphRuntime(), new InMemoryCheckpointStore());
    }

    public RunManager(CheckpointStore store) {
        this(new GraphRuntime(), store);
    }

    public RunManager(GraphRuntime runtime, CheckpointStore store) {
        this.runtime = runtime;
        this.store = store;
    }

    /** Stage 7: allow swapping the runtime (e.g. to inject a scheduler). */
    public void setRuntime(GraphRuntime runtime) {
        this.runtime = runtime;
    }

    // ============ Lifecycle ============

    /**
     * Start a new workflow run.
     *
     * @return ExecutionResult (SUCCEEDED / FAILED / PAUSED / CANCELLED)
     */
    public ExecutionResult start(Workflow workflow, Object input) {
        String runId = UUID.randomUUID().toString();
        Run run = new Run(runId, workflow, WorkflowState.of(input));
        activeRuns.put(runId, run);
        log.info("[{}] Run started, workflow='{}'", runId, workflow.name());
        return executeAndPersist(run);
    }

    /**
     * Resume a paused run (in-memory: Run is still cached).
     * <p>
     * Guard: only PAUSED runs can be resumed. Resuming a terminal run
     * (SUCCEEDED/FAILED/CANCELLED) would re-execute nodes - a duplicate
     * execution bug (found in Stage 7 code review).
     *
     * @return ExecutionResult (SUCCEEDED / FAILED / PAUSED / CANCELLED)
     */
    public ExecutionResult resume(String runId) {
        Run run = activeRuns.get(runId);
        if (run == null) {
            throw new WorkflowException("Run not found in memory: '" + runId
                    + "'. Use resume(runId, workflow) for checkpoint recovery.");
        }
        if (run.getStatus().isTerminal()) {
            throw new WorkflowException("Run '" + runId + "' is already "
                    + run.getStatus() + " - only PAUSED runs can be resumed");
        }
        log.info("[{}] Resuming from cursor='{}'", runId, run.getCursor());
        run.setStatus(RunState.RUNNING);
        return executeAndPersist(run);
    }

    /**
     * Resume a paused run from checkpoint (crash recovery).
     * Loads the checkpoint from the store and re-creates the Run.
     *
     * @param runId    the paused Run's id
     * @param workflow the Workflow definition (not stored in checkpoint)
     * @return ExecutionResult
     */
    public ExecutionResult resume(String runId, Workflow workflow) {
        Run run = activeRuns.get(runId);
        if (run == null) {
            Optional<Checkpoint> cp = store.load(runId);
            if (cp.isEmpty()) {
                throw new WorkflowException("No checkpoint found for run: '" + runId + "'");
            }
            run = Run.fromCheckpoint(cp.get(), workflow);
            activeRuns.put(runId, run);
            log.info("[{}] Restored from checkpoint, cursor='{}'", runId, run.getCursor());
        } else {
            log.info("[{}] Resuming from in-memory run, cursor='{}'", runId, run.getCursor());
        }
        if (run.getStatus().isTerminal()) {
            throw new WorkflowException("Run '" + runId + "' is already "
                    + run.getStatus() + " - only PAUSED runs can be resumed");
        }
        run.setStatus(RunState.RUNNING);
        return executeAndPersist(run);
    }

    /**
     * Request cancellation of a run. The run stops at the next node boundary.
     *
     * @return true if the run was found and cancellation requested
     */
    public boolean cancel(String runId) {
        Run run = activeRuns.get(runId);
        if (run == null) {
            return false;
        }
        run.cancel();
        log.info("[{}] Cancellation requested", runId);
        return true;
    }

    /** Get a run by id (null if not found). */
    public Run getRun(String runId) {
        return activeRuns.get(runId);
    }

    /**
     * Snapshot of currently tracked runs. Needed so a caller can cancel a
     * still-running start() (runId is generated internally and otherwise
     * only returned when start() completes).
     */
    public List<Run> listRuns() {
        return List.copyOf(activeRuns.values());
    }

    /**
     * Force a run into FAILED without executing further nodes.
     * Used by Stage 7 token-budget enforcement on a paused run.
     *
     * @return true if the run was found and moved to FAILED
     */
    public boolean fail(String runId, String reason) {
        Run run = activeRuns.get(runId);
        if (run == null || run.getStatus().isTerminal()) {
            return false;
        }
        run.setStatus(RunState.FAILED);
        run.setErrorMessage(reason);
        String node = run.getCursor() != null ? run.getCursor() : "?";
        run.getState().record(io.github.qwzhang01.agent.workflow.StepRecord.failed(node, 0, 0, reason));
        log.info("[{}] Run failed: {}", runId, reason);
        return true;
    }

    /** Get the checkpoint store (for testing / inspection). */
    public CheckpointStore getStore() {
        return store;
    }

    // ============ Internal ============

    private ExecutionResult executeAndPersist(Run run) {
        ExecutionResult result = runtime.execute(run);

        if (result.isPaused()) {
            // Save checkpoint for potential crash recovery
            String cpId = store.save(run.toCheckpoint());
            log.info("[{}] Paused at node '{}', checkpoint saved: {}", 
                    run.getRunId(), run.getCursor(), cpId);
            // Return result with the resume token
            return ExecutionResult.paused(
                    new ResumeToken(run.getRunId(), cpId, run.getCursor()),
                    run.getState());
        }

        // Terminal: clean up active runs (keep for a while for getRun queries)
        if (result.status().isTerminal()) {
            log.info("[{}] Run terminated: {}", run.getRunId(), result.status());
        }

        return result;
    }
}

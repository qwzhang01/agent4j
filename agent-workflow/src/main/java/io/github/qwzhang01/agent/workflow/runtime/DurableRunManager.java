package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.runtime.durable.RecoverySnapshot;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * Durable execution manager (Stage 3, harness roadmap).
 * <p>
 * Wraps a plain {@link RunManager} and adds the durable spine:
 * <ul>
 *   <li><b>RunStore</b> — every transition lands as an optimistic-locked
 *       row; recovery candidates come from the store, never from the JVM
 *       active map ("Scheduler only schedules persistent Runs").</li>
 *   <li><b>Lease</b> — two workers recovering the same run: only one wins
 *       the lease; the loser fails loudly.</li>
 *   <li><b>Definition guard</b> — a resume against a changed workflow
 *       definition (version+hash mismatch) is refused with
 *       {@code DEFINITION_VERSION_MISMATCH}, never silently resumed.</li>
 *   <li><b>Side-effect ledger</b> — nodes consult it before re-executing
 *       (a hit replays the recorded result; the external system is never
 *       hit twice for the same effect).</li>
 *   <li><b>Diagnostics</b> — {@link #recoverySnapshot(String)} assembles
 *       the pre-resume answer: where the run is, last event, last error,
 *       which effects already landed.</li>
 * </ul>
 * <p>
 * Execution itself is still delegated to the wrapped {@link RunManager}
 * (design decision D1 from Stage 7: this class adds the durability layer,
 * it does not replace the executor).
 */
public class DurableRunManager {

    private static final Logger log = LoggerFactory.getLogger(DurableRunManager.class);

    /** Lease TTL: a crashed holder's lease frees up after this window. */
    static final long DEFAULT_LEASE_TTL_MS = 60_000;

    private final RunManager delegate;
    private final RunStore runStore;
    private final RunLeaseRegistry leases;
    private final SideEffectLedger ledger;

    public DurableRunManager(RunManager delegate, RunStore runStore) {
        this(delegate, runStore, new RunLeaseRegistry(), null);
    }

    public DurableRunManager(RunManager delegate, RunStore runStore,
                             RunLeaseRegistry leases, SideEffectLedger ledger) {
        this.delegate = delegate;
        this.runStore = runStore;
        this.leases = leases;
        this.ledger = ledger;
    }

    // ============ Start ============

    /** Start a durable run: RunStore row created before execution begins. */
    public ExecutionResult start(Workflow workflow, Object input) {
        String runId = java.util.UUID.randomUUID().toString();
        return start(workflow, input, runId, null);
    }

    /**
     * Start with a caller-supplied runId and optional unified context.
     * The RunStore row is created (version 0) <b>before</b> the delegate
     * executes, so even a crash at the first node leaves a recovery
     * candidate behind. The runId is threaded into the delegate via a
     * bound RunContext, so the durable row and the live Run share one id.
     */
    public ExecutionResult start(Workflow workflow, Object input, String runId, RunContext ctx) {
        createRow(workflow, runId, "RUNNING");
        log.info("[{}] Durable run started, workflow='{}'@{}({})", runId,
                workflow.name(), workflow.version(), workflow.fingerprint());
        // Bind the caller's durable runId into the context unconditionally:
        // the RunStore row and the live Run must share one id, or recovery
        // can never find the run the delegate actually executed.
        RunContext effective = ctx == null
                ? RunContext.builder().runId(runId).build()
                : ctx.toBuilder().runId(runId).build();
        ExecutionResult result;
        try {
            result = delegate.start(workflow, input, effective);
        } catch (RuntimeException e) {
            transition(runId, "FAILED", null, e.getMessage());
            throw e;
        }
        transitionFromResult(runId, workflow, result);
        return result;
    }

    // ============ Resume (guarded, leased) ============

    /**
     * Resume a paused/waiting run with all Stage 3 guarantees:
     * definition-match check, lease acquisition, optimistic-locked
     * transition, ledger-aware re-execution (the delegate's nodes consult
     * {@link #ledger()}).
     */
    public ExecutionResult resume(String runId, Workflow workflow) {
        RunRecord row = runStore.get(runId)
                .orElseThrow(() -> new WorkflowException("No durable run row for '" + runId + "'"));

        // ---- Definition guard: refuse silent mismatch ----
        checkDefinition(runId, row, workflow);

        // ---- Lease: only one worker may resume this run ----
        String holder = "worker-" + ProcessHandle.current().pid()
                + ":" + Thread.currentThread().getId();
        if (!leases.tryAcquire(runId, holder, DEFAULT_LEASE_TTL_MS)) {
            String current = leases.holder(runId).orElse("unknown");
            throw new WorkflowException("Run '" + runId + "' is leased by " + current
                    + " - refusing concurrent resume");
        }

        try {
            // ---- Optimistic transition WAITING/PAUSED -> RUNNING ----
            transition(runId, "RUNNING", row.cursor(), null);

            ExecutionResult result = delegate.resume(runId, workflow);
            transitionFromResult(runId, workflow, result);
            return result;
        } catch (io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException e) {
            throw e;
        } finally {
            leases.release(runId, holder);
        }
    }

    // ============ Restart sweep ============

    /**
     * After a process restart: list recovery candidates from the store
     * (RUNNING / PAUSED / WAITING_APPROVAL rows), optionally filtering out
     * runs still leased by another live worker.
     */
    public List<RunRecord> listRecoveryCandidates() {
        return runStore.listRecoveryCandidates();
    }

    /** Pre-resume diagnostics: where, last event, last error, landed effects. */
    public RecoverySnapshot recoverySnapshot(String runId) {
        RunRecord row = runStore.get(runId)
                .orElseThrow(() -> new WorkflowException("No durable run row for '" + runId + "'"));
        return RecoverySnapshot.of(row, ledger);
    }

    // ============ Accessors ============

    public RunStore runStore() {
        return runStore;
    }

    public SideEffectLedger ledger() {
        return ledger;
    }

    public RunLeaseRegistry leases() {
        return leases;
    }

    public RunManager delegate() {
        return delegate;
    }

    // ============ Internal ============

    private void createRow(Workflow workflow, String runId, String status) {
        RunRecord row = new RunRecord(runId, workflow.name(), workflow.version(),
                workflow.fingerprint(), status, null, 0, 0, null, null,
                System.currentTimeMillis(), System.currentTimeMillis(), 0, List.of());
        try {
            runStore.create(row);
        } catch (io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException dup) {
            throw new WorkflowException("Run id '" + runId + "' already exists in the RunStore");
        }
    }

    private void transition(String runId, String status, String cursor, String error) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<RunRecord> opt = runStore.get(runId);
            if (opt.isEmpty()) {
                return; // row never created (legacy path) - nothing to persist
            }
            RunRecord row = opt.get();
            RunRecord updated = new RunRecord(row.runId(), row.workflowName(),
                    row.workflowVersion(), row.workflowHash(), status, cursor,
                    row.stepsExecuted(), row.lastEventSeq(), row.checkpointId(),
                    error, row.createdAt(), System.currentTimeMillis(),
                    row.version(), row.lastTrace());
            try {
                runStore.update(updated);
                return;
            } catch (io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException race) {
                if (attempt == 2) {
                    log.warn("[{}] RunStore update lost the race 3x for status {} - giving up", runId, status);
                }
                // reload and retry: another writer advanced the version
            }
        }
    }

    private void transitionFromResult(String runId, Workflow workflow, ExecutionResult result) {
        Run run = delegate.getRun(runId);
        String cursor = run != null ? run.getCursor() : null;
        String status = switch (result.status()) {
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case PAUSED -> "PAUSED";
            case CANCELLED -> "CANCELLED";
        };
        String error = result.errorMessage();
        // Persist the checkpoint id + event position alongside the status.
        Optional<Checkpoint> cp = delegate.getStore().load(runId);
        long seq = cp.map(Checkpoint::lastEventSeq).orElse(0L);
        String cpId = cp.map(Checkpoint::checkpointId).orElse(null);
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<RunRecord> opt = runStore.get(runId);
            if (opt.isEmpty()) {
                return;
            }
            RunRecord row = opt.get();
            RunRecord updated = new RunRecord(row.runId(), row.workflowName(),
                    row.workflowVersion(), row.workflowHash(), status, cursor,
                    row.stepsExecuted(), seq, cpId, error, row.createdAt(),
                    System.currentTimeMillis(), row.version(), row.lastTrace());
            try {
                runStore.update(updated);
                return;
            } catch (io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException race) {
                // retry with a fresh row
            }
        }
        log.warn("[{}] RunStore final-status update lost the race 3x - row may lag", runId);
    }

    private void checkDefinition(String runId, RunRecord row, Workflow workflow) {
        boolean sameName = row.workflowName().equals(workflow.name());
        boolean legacy = row.workflowHash().isEmpty();
        boolean sameVersion = row.workflowVersion().equals(workflow.version());
        boolean sameHash = row.workflowHash().equals(workflow.fingerprint());
        if (sameName && (legacy || (sameVersion && sameHash))) {
            return;
        }
        String msg = "[DEFINITION_VERSION_MISMATCH] Run '" + runId + "' started under '"
                + row.workflowName() + "@" + row.workflowVersion() + "(" + row.workflowHash()
                + ")' but resume was given '" + workflow.name() + "@" + workflow.version()
                + "(" + workflow.fingerprint() + ")' - refusing silent resume";
        log.error(msg);
        throw new WorkflowException(msg);
    }
}

package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.runtime.durable.DistributedRunControl;
import io.github.qwzhang01.agent.workflow.runtime.durable.RecoverySnapshot;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunLeases;
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
    private final RunLeases leases;
    private final SideEffectLedger ledger;
    private final long leaseTtlMs;
    private final DistributedRunControl control;

    public DurableRunManager(RunManager delegate, RunStore runStore) {
        this(delegate, runStore, new RunLeaseRegistry(), null, DEFAULT_LEASE_TTL_MS);
    }

    /** Legacy 4-arg signature kept source-compatible (RunLeaseRegistry implements RunLeases). */
    public DurableRunManager(RunManager delegate, RunStore runStore,
                             RunLeaseRegistry leases, SideEffectLedger ledger) {
        this(delegate, runStore, (RunLeases) leases, ledger, DEFAULT_LEASE_TTL_MS);
    }

    /**
     * Stage 8.1: leases are pluggable ({@link RunLeases}) so a JDBC/Redis
     * backend can back cross-instance recovery; the default stays the
     * in-memory reference registry.
     */
    public DurableRunManager(RunManager delegate, RunStore runStore,
                             RunLeases leases, SideEffectLedger ledger) {
        this(delegate, runStore, leases, ledger, DEFAULT_LEASE_TTL_MS);
    }

    /**
     * Stage 8.1: with an explicit lease TTL (tests and deployments whose
     * lease backend policy differs from the 60s default).
     */
    public DurableRunManager(RunManager delegate, RunStore runStore,
                             RunLeases leases, SideEffectLedger ledger, long leaseTtlMs) {
        this.delegate = delegate;
        this.runStore = runStore;
        this.leases = leases;
        this.ledger = ledger;
        this.leaseTtlMs = leaseTtlMs;
        this.control = new DistributedRunControl(runStore);
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
        if (!leases.tryAcquire(runId, holder, leaseTtlMs)) {
            String current = leases.holder(runId).orElse("unknown");
            throw new WorkflowException("Run '" + runId + "' is leased by " + current
                    + " - refusing concurrent resume");
        }

        // Stage 8.1 cross-instance guard: the row must still be a recovery
        // candidate. Another instance may have CAS-cancelled it (or the run
        // finished elsewhere) while a stale PAUSED checkpoint still exists.
        // Lease + row eligibility must BOTH hold before any node executes.
        control.assertResumable(runId);

        // Stage 8.1 fix: heartbeat. A resume that legitimately runs longer
        // than the TTL previously looked like a crashed holder — another
        // worker took the lease mid-flight and BOTH executed (duplicate
        // execution bug). A daemon heartbeat renews while we hold; if the
        // renew fails (lost ownership), the in-flight execution is aborted
        // at the next node boundary via the run's cancel flag.
        java.util.concurrent.ScheduledExecutorService heartbeat = null;
        try {
            heartbeat = startHeartbeat(runId, holder, delegate.getRun(runId));
            // ---- Optimistic transition WAITING/PAUSED -> RUNNING ----
            transition(runId, "RUNNING", row.cursor(), null);

            ExecutionResult result = delegate.resume(runId, workflow);
            transitionFromResult(runId, workflow, result);
            return result;
        } catch (io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException e) {
            throw e;
        } finally {
            if (heartbeat != null) {
                heartbeat.shutdownNow();
            }
            leases.release(runId, holder);
        }
    }

    /**
     * Heartbeat loop: renew the lease every TTL/3. When renewal fails the
     * holder lost ownership (lease expired + taken over) — cancel the live
     * run so the duplicate execution stops at the next node boundary,
     * loudly.
     */
    private java.util.concurrent.ScheduledExecutorService startHeartbeat(
            String runId, String holder, Run liveRun) {
        long period = Math.max(250, leaseTtlMs / 3);
        java.util.concurrent.ScheduledExecutorService hb =
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "run-lease-heartbeat-" + runId);
                    t.setDaemon(true);
                    return t;
                });
        hb.scheduleAtFixedRate(() -> {
            boolean stillOurs;
            try {
                stillOurs = leases.renew(runId, holder, leaseTtlMs);
            } catch (RuntimeException e) {
                stillOurs = false; // backend hiccup: treat as lost, stop touching the run
            }
            if (!stillOurs) {
                log.error("[{}] Lease heartbeat lost ownership - cancelling the in-flight "
                        + "resume to stop duplicate execution", runId);
                if (liveRun != null) {
                    liveRun.cancel();
                }
                return;
            }
            // Stage 8.1 cross-instance cancel: watch the run row. Another
            // instance's operator CAS-cancels the row; we observe it here and
            // stop the in-flight run within one poll period (the lease alone
            // cannot carry that signal — it only knows ownership, not intent).
            try {
                if (runStore.get(runId).map(r -> !r.isRecoveryCandidate()).orElse(false)) {
                    log.warn("[{}] Run row observed terminal status while resuming - "
                            + "cancelling the in-flight run (cross-instance control)", runId);
                    if (liveRun != null) {
                        liveRun.cancel();
                    }
                }
            } catch (RuntimeException e) {
                // Row watch must never kill a healthy run on a store hiccup:
                // the lease is still ours; keep executing (fail-open on the
                // control channel, fail-closed on ownership).
            }
        }, period, period, java.util.concurrent.TimeUnit.MILLISECONDS);
        return hb;
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

    /**
     * Stage 8.1: cancel a run from this instance. Two channels:
     * <ol>
     *   <li><b>Local</b> — if the run is live in this JVM ({@link
     *       RunManager#getRun}), flip its cancel flag directly (fastest
     *       path, no row round-trip).</li>
     *   <li><b>Durable</b> — CAS the run row to CANCELLED (the durable
     *       command). The owning instance's heartbeat observes the row
     *       within one poll period; a future resume of a stale checkpoint
     *       is refused by the resume guard.</li>
     * </ol>
     * Order matters: the local flag first (best-effort immediate stop),
     * then the row (durable, cross-instance).
     */
    public boolean cancel(String runId, String reason) {
        Run live = delegate.getRun(runId);
        boolean localFlipped = live != null && live.getStatus() != null
                && !live.getStatus().isTerminal();
        if (live != null && localFlipped) {
            live.cancel();
        }
        return control.cancel(runId, reason) == DistributedRunControl.CancelOutcome.CANCELLED;
    }

    /** The cross-instance control plane (for operators / tests). */
    public DistributedRunControl control() {
        return control;
    }

    // ============ Accessors ============

    public RunStore runStore() {
        return runStore;
    }

    public SideEffectLedger ledger() {
        return ledger;
    }

    public RunLeases leases() {
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

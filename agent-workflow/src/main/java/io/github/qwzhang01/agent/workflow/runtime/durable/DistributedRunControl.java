package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.Optional;

/**
 * Cross-instance control plane for durable runs (Stage 8.1, harness
 * roadmap): "the run row is the control channel".
 * <p>
 * A {@link RunStore} shared by two runtime instances is already the
 * source of truth for run state; this class turns that fact into the
 * three cross-instance operations the roadmap requires:
 * <ol>
 *   <li><b>Cancel</b> — any instance CASes the run row to CANCELLED;
 *       the row transition is the durable command. The owning instance
 *       (if any) observes the row through its heartbeat loop and stops
 *       the in-flight run at the next node boundary (within one poll).</li>
 *   <li><b>Resume guard</b> — a resume against a row another instance
 *       already CAS-cancelled (or otherwise terminated) is refused
 *       loudly, never blindly revived.</li>
 *   <li><b>Approval callback</b> — decisions live in a shared {@link
 *       ApprovalStore} (instance A's operator decision is instance B's
 *       green light); the idempotent derived approvalId keeps the two
 *       instances on the same row.</li>
 * </ol>
 * Cancel-vs-cancel and cancel-vs-final-status races are settled by the
 * row's optimistic {@code version}: exactly one writer wins each CAS;
 * losers get {@link RunRecord#isRecoveryCandidate()} returning false on
 * reload and fail loudly.
 * <p>
 * This class deliberately holds no execution logic: it is the operator
 * entry point that writes control signals, paired with {@link
 * io.github.qwzhang01.agent.workflow.runtime.DurableRunManager} which
 * executes and observes them.
 */
public final class DistributedRunControl {

    private final RunStore runStore;

    public DistributedRunControl(RunStore runStore) {
        this.runStore = runStore;
    }

    /**
     * Cancel a run from any instance. The durable command is the row
     * transition itself: CAS the row to CANCELLED only when it is still
     * a recovery candidate (RUNNING / PAUSED / WAITING_APPROVAL).
     * <p>
     * Return semantics (mirrors RunManager.cancel):
     * <ul>
     *   <li>{@code CANCELLED} — this call flipped the row; the owner
     *       (if any) stops at the next node boundary.</li>
     *   <li>{@code ALREADY_TERMINAL} — the row is in a terminal status
     *       (another instance finished/failed/cancelled it first).</li>
     *   <li>{@code NO_ROW} — no durable row exists for the runId.</li>
     * </ul>
     */
    public CancelOutcome cancel(String runId, String reason) {
        Optional<RunRecord> opt = runStore.get(runId);
        if (opt.isEmpty()) {
            return CancelOutcome.NO_ROW;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            RunRecord row = opt.get();
            if (!row.isRecoveryCandidate()) {
                return CancelOutcome.ALREADY_TERMINAL;
            }
            RunRecord cancelledRow = new RunRecord(row.runId(), row.workflowName(),
                    row.workflowVersion(), row.workflowHash(), "CANCELLED", row.cursor(),
                    row.stepsExecuted(), row.lastEventSeq(), row.checkpointId(),
                    reason, row.createdAt(), System.currentTimeMillis(),
                    row.version(), row.lastTrace(), row.tenantId(), row.versions());
            try {
                runStore.update(cancelledRow);
                return CancelOutcome.CANCELLED;
            } catch (VersionConflictException race) {
                // Another writer moved the row first: reload and re-apply.
                opt = runStore.get(runId);
                if (opt.isEmpty()) {
                    return CancelOutcome.NO_ROW;
                }
            }
        }
        // Lost the race 3x: the row is hotly contested; report terminal
        // only if it truly is, otherwise surface the contention loudly.
        RunRecord row = runStore.get(runId).orElse(null);
        if (row == null) {
            return CancelOutcome.NO_ROW;
        }
        return row.isRecoveryCandidate() ? CancelOutcome.CONTENDED : CancelOutcome.ALREADY_TERMINAL;
    }

    /** Result of a cross-instance cancel attempt. */
    public enum CancelOutcome {
        CANCELLED, ALREADY_TERMINAL, NO_ROW, CONTENDED
    }

    /**
     * Guard for {@code DurableRunManager.resume}: the run row must still
     * be a recovery candidate. A row another instance CAS-cancelled (or
     * that finished/failed) must not be blindly revived from a stale
     * checkpoint — the checkpoint store lags the run row on purpose
     * (rows are written at every transition; checkpoints only on pause).
     * <p>
     * Callers should invoke this immediately after acquiring the lease:
     * lease ownership and row eligibility must BOTH hold before any
     * node executes.
     */
    public void assertResumable(String runId) {
        RunRecord row = runStore.get(runId).orElseThrow(() ->
                new IllegalStateException("No durable run row for '" + runId + "'"));
        if (!row.isRecoveryCandidate()) {
            throw new IllegalStateException("Run '" + runId + "' is " + row.status()
                    + " in the RunStore - refusing to revive from a stale checkpoint"
                    + " (cross-instance cancel/finalize guard)");
        }
    }

    /**
     * Whether the run row is still awaiting this approval — the
     * precondition for landing an operator decision. A run already
     * CANCELLED (say, by another instance's operator) should not accept
     * a late APPROVED that would read as a green light on a dead run.
     */
    public boolean approvalStillRelevant(String runId) {
        return runStore.get(runId)
                .map(RunRecord::isRecoveryCandidate)
                .orElse(false);
    }
}

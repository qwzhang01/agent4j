package io.github.qwzhang01.agent.core.approval;

import java.util.List;
import java.util.Optional;

/**
 * Durable approval persistence (Stage 3.4, harness roadmap).
 * <p>
 * The whole point of Stage 3.4: a decision must survive process restarts
 * and never depend on JVM memory. This store is the persistence boundary;
 * implementations range from {@code InMemoryApprovalStore} (tests) to a
 * future JDBC/Postgres backend (production).
 * <p>
 * Contract highlights (all enforced by the reference test suite):
 * <ul>
 *   <li><b>Idempotent submit</b> — resubmitting the same logical request
 *       (same derived approvalId) returns the original and never creates
 *       a second row or a second decision.</li>
 *   <li><b>Optimistic decision</b> — {@link #decide} carries the base
 *       version; a stale version is rejected, so two concurrent deciders
 *       cannot both land; a second decision on the same request is
 *       rejected (no double-decide).</li>
 *   <li><b>Expiry</b> — {@link #expireOverdue} flips overdue PENDING
 *       requests to EXPIRED with a distinct failure semantic; the run
 *       fails timeout-shaped, not rejection-shaped.</li>
 *   <li><b>Revocation</b> — {@link #revoke} withdraws an APPROVED request;
 *       the run must not proceed even though a green light once existed.</li>
 * </ul>
 */
public interface ApprovalStore {

    /**
     * Persist a new request, or return the existing one for the same
     * approvalId (idempotent submit). Never creates duplicates.
     *
     * @return the stored request: the original when the id already exists
     */
    ApprovalRequest submit(ApprovalRequest request);

    /** Load by approvalId. */
    Optional<ApprovalRequest> get(String approvalId);

    /**
     * Land a decision with optimistic locking. Rejects when:
     * the request is missing, already terminal, or {@code decision.version()}
     * does not match the stored {@link ApprovalRequest#version()}.
     *
     * @param targetStatus APPROVED or REJECTED — the store does not guess
     *                     the semantic from free text; the caller states it
     * @return the updated request (status = targetStatus, version+1)
     * @throws ApprovalConflictException on any of the rejection cases above
     */
    ApprovalRequest decide(ApprovalRequest request, ApprovalDecision decision,
                           ApprovalStatus targetStatus);

    /**
     * Withdraw an earlier approval. Only APPROVED requests can be revoked;
     * revoking a PENDING or already-terminal request is a conflict.
     */
    ApprovalRequest revoke(String approvalId, ApprovalDecision revocation);

    /** Flip overdue PENDING requests to EXPIRED. Returns the flipped ids. */
    List<String> expireOverdue(long nowEpochMs);

    /** All PENDING requests for a run — recovery scans use this after restart. */
    List<ApprovalRequest> pendingForRun(String runId);

    /** All PENDING requests across runs — restart sweep entry point. */
    List<ApprovalRequest> allPending();
}

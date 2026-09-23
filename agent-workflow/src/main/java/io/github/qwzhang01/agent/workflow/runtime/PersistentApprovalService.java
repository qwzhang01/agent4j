package io.github.qwzhang01.agent.workflow.runtime;

import io.github.qwzhang01.agent.core.approval.ApprovalDecision;
import io.github.qwzhang01.agent.core.approval.ApprovalRequest;
import io.github.qwzhang01.agent.core.approval.ApprovalStatus;
import io.github.qwzhang01.agent.core.approval.ApprovalStore;
import io.github.qwzhang01.agent.workflow.ApprovalService;

import java.util.Objects;
import java.util.Optional;

/**
 * Workflow-layer {@link ApprovalService} backed by the durable
 * {@link ApprovalStore} (Stage 3.4, harness roadmap).
 * <p>
 * Workflow Approval and Tool Approval reuse the same persistence protocol
 * ({@link ApprovalRequest}/{@link ApprovalDecision}/{@link ApprovalStore})
 * — different service interfaces, one durable substrate. A decision
 * survives restarts; {@link #checkDecision} after a restart reads the
 * store, never JVM memory.
 * <p>
 * Idempotency: the approvalId is derived (runId:nodeId). A node that
 * pauses and re-executes re-submits the same logical request; the store
 * returns the original — no duplicate rows, no double decisions.
 * <p>
 * Distinct failure semantics (roadmap 3.4): REJECTED throws
 * {@code ApprovalRejectedException} (business rejection), EXPIRED throws
 * an expiry exception (timeout-shaped), REVOKED throws a revocation
 * exception. {@link #checkDecision} returns null while PENDING so the
 * node re-pauses.
 */
public final class PersistentApprovalService implements ApprovalService {

    /** Expiry semantic: run fails timeout-shaped. */
    public static final class ApprovalExpiredException extends RuntimeException {
        public ApprovalExpiredException(String approvalId) {
            super("[APPROVAL_EXPIRED] " + approvalId);
        }
    }

    /** Revocation semantic: run must not proceed despite an earlier yes. */
    public static final class ApprovalRevokedException extends RuntimeException {
        public ApprovalRevokedException(String approvalId) {
            super("[APPROVAL_REVOKED] " + approvalId);
        }
    }

    private final ApprovalStore store;
    private final String riskLevel;
    private final long ttlMillis;

    /**
     * @param store     durable protocol store
     * @param riskLevel label recorded on every request (e.g. "HIGH")
     * @param ttlMillis request expiry; {@code <=0} = never expires
     */
    public PersistentApprovalService(ApprovalStore store, String riskLevel, long ttlMillis) {
        this.store = Objects.requireNonNull(store);
        this.riskLevel = riskLevel == null ? "UNSPECIFIED" : riskLevel;
        this.ttlMillis = ttlMillis;
    }

    /** No expiry, risk label UNSPECIFIED. */
    public PersistentApprovalService(ApprovalStore store) {
        this(store, "UNSPECIFIED", 0);
    }

    // Sync mode (Stage 5 compat)

    @Override
    public boolean approve(Request request) {
        // Sync mode has no runId: derive a pseudo id so the durable row is
        // still unique per node call (teaching v1; production uses async).
        String approvalId = ApprovalRequest.idForNode("sync", request.nodeId());
        ApprovalRequest row = submit(approvalId, "sync", request.nodeId(),
                hashOf(request.payload()), request.summary());
        ApprovalDecision decision =
                ApprovalDecision.of("sync-approver", request.summary(), row.version());
        ApprovalRequest decided = store.decide(row, decision, ApprovalStatus.APPROVED);
        return decided.status() == ApprovalStatus.APPROVED;
    }

    // Async mode (Stage 6 pause/resume)

    @Override
    public void requestApproval(String runId, String nodeId, String summary, Object payload) {
        String approvalId = ApprovalRequest.idForNode(runId, nodeId);
        submit(approvalId, runId, nodeId, hashOf(payload), summary);
    }

    @Override
    public Boolean checkDecision(String runId, String nodeId) {
        String approvalId = ApprovalRequest.idForNode(runId, nodeId);
        Optional<ApprovalRequest> opt = store.get(approvalId);
        if (opt.isEmpty()) {
            // No request on record (e.g. store wiped): treat as pending so
            // the node re-requests rather than silently proceeding.
            return null;
        }
        ApprovalRequest row = opt.get();
        // Flip overdue PENDING rows first - expiry is the store's job.
        if (row.isOverdue(System.currentTimeMillis())) {
            store.expireOverdue(System.currentTimeMillis());
            row = store.get(approvalId).orElse(row);
        }
        return switch (row.status()) {
            case PENDING -> null;                       // keep waiting
            case APPROVED -> Boolean.TRUE;
            case REJECTED -> Boolean.FALSE;
            case EXPIRED -> {
                throw new ApprovalExpiredException(approvalId);
            }
            case REVOKED -> {
                throw new ApprovalRevokedException(approvalId);
            }
        };
    }

    // Typed facade for operators / tests

    /** Land an APPROVED decision (operator action). */
    public ApprovalRequest approve(String runId, String nodeId, String decidedBy, String reason) {
        return decide(runId, nodeId, ApprovalStatus.APPROVED, decidedBy, reason);
    }

    /** Land a REJECTED decision (operator action). */
    public ApprovalRequest reject(String runId, String nodeId, String decidedBy, String reason) {
        return decide(runId, nodeId, ApprovalStatus.REJECTED, decidedBy, reason);
    }

    private ApprovalRequest decide(String runId, String nodeId, ApprovalStatus target,
                                   String decidedBy, String reason) {
        String approvalId = ApprovalRequest.idForNode(runId, nodeId);
        ApprovalRequest row = store.get(approvalId)
                .orElseThrow(() -> new IllegalStateException(
                        "No approval request for " + approvalId));
        return store.decide(row, ApprovalDecision.of(decidedBy, reason, row.version()), target);
    }

    /** Withdraw an earlier approval (operator action). */
    public ApprovalRequest revoke(String runId, String nodeId, String by, String reason) {
        String approvalId = ApprovalRequest.idForNode(runId, nodeId);
        ApprovalRequest row = store.get(approvalId)
                .orElseThrow(() -> new IllegalStateException(
                        "No approval request for " + approvalId));
        return store.revoke(approvalId, ApprovalDecision.of(by, reason, row.version()));
    }

    /** The durable store (for restart sweeps). */
    public ApprovalStore store() {
        return store;
    }

    private ApprovalRequest submit(String approvalId, String runId, String nodeId,
                                   String toolCallHash, String summary) {
        long now = System.currentTimeMillis();
        ApprovalRequest request = new ApprovalRequest(
                approvalId, runId, nodeId, toolCallHash,
                "workflow:node", riskLevel, summary,
                ttlMillis <= 0 ? 0 : now + ttlMillis, now,
                ApprovalStatus.PENDING, null, 0);
        return store.submit(request); // idempotent: original wins on duplicate
    }

    private static String hashOf(Object payload) {
        if (payload == null) {
            return "none";
        }
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(payload).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package io.github.qwzhang01.agent.core.approval;

/**
 * Raised by {@link ApprovalStore#decide}/{@link ApprovalStore#revoke} when
 * the transition violates the protocol (Stage 3.4): stale version, already
 * terminal, or already decided. Carries the offending request's state so
 * callers can map to their layer-specific failure semantics.
 */
public class ApprovalConflictException extends RuntimeException {

    private final ApprovalRequest offending;

    public ApprovalConflictException(String message, ApprovalRequest offending) {
        super(message);
        this.offending = offending;
    }

    /** The stored request that refused the transition. */
    public ApprovalRequest offendingRequest() {
        return offending;
    }
}

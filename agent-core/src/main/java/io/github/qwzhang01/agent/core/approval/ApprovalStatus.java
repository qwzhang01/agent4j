package io.github.qwzhang01.agent.core.approval;

/**
 * Lifecycle status of an approval request (Stage 3.4, harness roadmap).
 * <p>
 * Each terminal status carries its own failure semantics — the roadmap
 * forbids collapsing "expired", "rejected", "revoked" and "duplicate"
 * into one generic refusal:
 * <ul>
 *   <li>{@link #PENDING} — requested, awaiting a human decision.</li>
 *   <li>{@link #APPROVED} — a human approved; the run may proceed.</li>
 *   <li>{@link #REJECTED} — a human rejected; the requesting step fails
 *       with a business rejection (not a system failure).</li>
 *   <li>{@link #EXPIRED} — no decision before {@code expiresAt}; the run
 *       fails with a timeout-shaped error, distinct from rejection.</li>
 *   <li>{@link #REVOKED} — an earlier approval was withdrawn; the run must
 *       not proceed even though an APPROVED decision once existed.</li>
 * </ul>
 */
public enum ApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED,
    EXPIRED,
    REVOKED;

    /** Terminal once a human decision (or expiry/revocation) has landed. */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /** Whether the requesting step may proceed under this status. */
    public boolean isGreenLight() {
        return this == APPROVED;
    }
}

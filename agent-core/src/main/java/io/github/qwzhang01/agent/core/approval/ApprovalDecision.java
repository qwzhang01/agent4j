package io.github.qwzhang01.agent.core.approval;

import java.util.Objects;

/**
 * The human decision landed on an {@link ApprovalRequest} (Stage 3.4).
 *
 * @param decidedBy who made the decision (user id, approver role)
 * @param decidedAt epoch millis when the decision was made
 * @param reason    free-form justification (audit trail)
 * @param version   the {@link ApprovalRequest#version()} this decision
 *                  transitions from — stale versions are rejected by the
 *                  store, so two concurrent decisions cannot both land
 */
public record ApprovalDecision(String decidedBy, long decidedAt, String reason, long version) {

    public ApprovalDecision {
        Objects.requireNonNull(decidedBy, "decidedBy must not be null");
    }

    public static ApprovalDecision of(String decidedBy, String reason, long fromVersion) {
        return new ApprovalDecision(decidedBy, System.currentTimeMillis(), reason, fromVersion);
    }
}

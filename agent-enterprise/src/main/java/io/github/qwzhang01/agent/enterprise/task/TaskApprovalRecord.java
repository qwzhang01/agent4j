package io.github.qwzhang01.agent.enterprise.task;

import java.time.Instant;
import java.util.Objects;

/**
 * Task-level approval evidence (Stage 15 M15.4, D6 "the node keeps the
 * process").
 * <p>
 * The SSOT of "who green-lit this business action": approver, decision,
 * reason, time. Task-level approval answers "may this business proceed"
 * (the supervisor's judgment); tool-level approval (Stage 9) answers "may
 * this call be executed" (the governance gate). Both layers appear in the
 * same enterprise scenario - that is defense in depth, not redundancy.
 *
 * @param taskId    the task being decided on
 * @param approverId who decided (must be a user with supervisor powers in
 *                  real deployments; v1 enforcement lives in the caller)
 * @param decision  APPROVED or REJECTED
 * @param reason    free-text justification (the audit evidence)
 * @param at        decision time
 */
public record TaskApprovalRecord(
        String taskId,
        String approverId,
        Decision decision,
        String reason,
        Instant at
) {

    public enum Decision {
        APPROVED,
        REJECTED
    }

    public TaskApprovalRecord {
        requireText(taskId, "taskId");
        requireText(approverId, "approverId");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(at, "at must not be null");
    }

    // ============ Helpers ============

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

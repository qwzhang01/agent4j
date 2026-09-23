package io.github.qwzhang01.agent.core.approval;

import java.util.Objects;

/**
 * A durable human-approval request (, harness roadmap).
 * <p>
 * One protocol for both layers: Workflow approval "approve this refund
 * node" and Tool approval "approve delete_file(/tmp/x)". The two layers
 * implement different service interfaces, but both persist through this
 * same model — a decision survives process restarts and never depends on
 * JVM memory.
 * <p>
 * Identity: {@code approvalId} must be <b>derived and idempotent</b> from
 * the request itself (e.g. runId:stepId for workflow, runId:toolCallHash
 * for tools). Submitting the same logical request twice yields the same
 * approvalId — the store keeps the original, no duplicate request rows.
 * <p>
 * The {@code status} field is the mutable corner of an otherwise immutable
 * record: every transition produces a new instance with an incremented
 * {@code version} (optimistic lock, see {@link ApprovalStore}).
 *
 * @param approvalId derived idempotent id (runId:stepId or runId:toolCallHash)
 * @param runId the run awaiting the decision
 * @param stepId workflow node id or tool call id (layer-specific)
 * @param toolCallHash SHA-256 prefix of the tool call / payload being approved
 * @param requestedBy who or what raised the request (agent id, node id)
 * @param riskLevel free-form risk label (e.g. LOW / HIGH / DESTRUCTIVE)
 * @param summary human-readable what-is-being-approved text
 * @param expiresAt epoch millis; 0 = no expiry
 * @param createdAt epoch millis when the request was raised
 * @param status current lifecycle status
 * @param decision the landed decision (null while PENDING)
 * @param version optimistic-lock version, 0 on creation
 */
public record ApprovalRequest(
        String approvalId,
        String runId,
        String stepId,
        String toolCallHash,
        String requestedBy,
        String riskLevel,
        String summary,
        long expiresAt,
        long createdAt,
        ApprovalStatus status,
        ApprovalDecision decision,
        long version) {

    public ApprovalRequest {
        Objects.requireNonNull(approvalId, "approvalId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(stepId, "stepId must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }

    /** Derived idempotent approvalId for a workflow-node approval. */
    public static String idForNode(String runId, String nodeId) {
        return runId + ":" + nodeId;
    }

    /** Derived idempotent approvalId for a tool-call approval. */
    public static String idForToolCall(String runId, String toolCallHash) {
        return runId + ":tool:" + toolCallHash;
    }

    /** True when an expiry is set and has passed. */
    public boolean isExpired(long nowEpochMs) {
        return expiresAt > 0 && nowEpochMs >= expiresAt;
    }

    /** PENDING + past expiry — the store flips these to EXPIRED on read/scan. */
    public boolean isOverdue(long nowEpochMs) {
        return status == ApprovalStatus.PENDING && isExpired(nowEpochMs);
    }
}

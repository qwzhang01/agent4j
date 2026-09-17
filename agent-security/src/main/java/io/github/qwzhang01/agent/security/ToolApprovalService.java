package io.github.qwzhang01.agent.security;

import io.github.qwzhang01.agent.core.model.ToolCall;

/**
 * Tool-layer approval service (Stage 9 D3/D4).
 * <p>
 * Independent from the Workflow-layer {@code ApprovalService} (Stage 5/6),
 * but shares the same design philosophy (sync + async modes).
 * <p>
 * Workflow approval granularity = node ("approve this refund node");
 * Tool approval granularity = tool call ("approve delete_file(/tmp/x)").
 * Different context, different payload, so separate interface.
 */
public interface ToolApprovalService {

    /**
     * Three-way approval verdict. {@link Verdict#PENDING} means the run
     * must pause ({@code WAITING_APPROVAL}) instead of blocking the loop
     * thread or collapsing the wait into a denial.
     */
    enum Verdict {
        APPROVED,
        REJECTED,
        PENDING
    }

    /**
     * Synchronous approval: blocks until a human decision is made.
     * <p>
     * Used in interactive mode (user is present, confirms on the spot).
     * Implementations that can pause should override {@link #verdict}
     * instead — this method cannot express PENDING.
     *
     * @param toolCall the tool call requesting approval
     * @param runId    the run context (null if not run-scoped)
     * @return true to approve, false to reject
     */
    boolean request(ToolCall toolCall, String runId);

    /**
     * Non-blocking verdict. Default maps {@link #request} onto APPROVED /
     * REJECTED so existing blocking services keep working. Durable
     * implementations return {@link Verdict#PENDING} until a human lands
     * a decision in the {@link io.github.qwzhang01.agent.core.approval.ApprovalStore}.
     */
    default Verdict verdict(ToolCall toolCall, String runId) {
        return request(toolCall, runId) ? Verdict.APPROVED : Verdict.REJECTED;
    }
}

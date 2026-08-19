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
     * Synchronous approval: blocks until a human decision is made.
     * <p>
     * Used in interactive mode (user is present, confirms on the spot).
     *
     * @param toolCall the tool call requesting approval
     * @param runId    the run context (null if not run-scoped)
     * @return true to approve, false to reject
     */
    boolean request(ToolCall toolCall, String runId);
}

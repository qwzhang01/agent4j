package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.workflow.ApprovalService;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;

/**
 * Human-in-the-loop checkpoint: asks an {@link ApprovalService} for a
 * decision before the workflow may proceed.
 * <p>
 * Two modes (design decision D6):
 * <p>
 * <b>Sync mode</b> (Stage 5): when {@code ctx.runId() == null} (no RunManager),
 * calls {@code approve()} synchronously. Blocks the thread. On reject,
 * throws {@link WorkflowException.ApprovalRejectedException}.
 * <p>
 * <b>Async mode</b> (Stage 6): when {@code ctx.runId() != null} (via RunManager),
 * calls {@code requestApproval()} then throws {@link PauseException} to suspend
 * the run. On resume ({@code ctx.isResuming() == true}), calls
 * {@code checkDecision()} to get the result. This enables pause-resume
 * without blocking a thread.
 * <p>
 * On approval, the node passes its input through unchanged to downstream nodes.
 */
public final class HumanApprovalNode implements WorkflowNode {

    private final String id;
    private final String summary;
    private final ApprovalService approvalService;

    private HumanApprovalNode(String id, String summary, ApprovalService approvalService) {
        this.id = id;
        this.summary = summary;
        this.approvalService = approvalService;
    }

    public static HumanApprovalNode of(String id, String summary, ApprovalService service) {
        return new HumanApprovalNode(id, summary, service);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        if (ctx.runId() != null) {
            // ---- Stage 6: async pause-resume mode ----
            return executeAsync(ctx);
        } else {
            // ---- Stage 5: sync blocking mode ----
            return executeSync(ctx);
        }
    }

    // ============ Sync (Stage 5) ============

    private NodeResult executeSync(NodeContext ctx) {
        boolean approved = approvalService.approve(
                new ApprovalService.Request(id, summary, ctx.input()));
        if (!approved) {
            throw new WorkflowException.ApprovalRejectedException(id);
        }
        return NodeResult.of(ctx.input());
    }

    // ============ Async (Stage 6) ============

    private NodeResult executeAsync(NodeContext ctx) throws PauseException {
        if (ctx.isResuming()) {
            // Resume path: check the decision
            Boolean decision = approvalService.checkDecision(ctx.runId(), id);
            if (decision == null) {
                // Still pending -> pause again
                throw new PauseException(id, "Approval still pending: " + summary);
            }
            if (!decision) {
                throw new WorkflowException.ApprovalRejectedException(id);
            }
            // Approved: pass payload through
            return NodeResult.of(ctx.input());
        } else {
            // First execution: request approval, then pause
            approvalService.requestApproval(ctx.runId(), id, summary, ctx.input());
            throw new PauseException(id, "Waiting for approval: " + summary);
        }
    }
}

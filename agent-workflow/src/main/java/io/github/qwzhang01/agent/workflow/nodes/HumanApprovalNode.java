package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.workflow.ApprovalService;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

/**
 * Human-in-the-loop checkpoint: asks an {@link ApprovalService} for a
 * decision before the workflow may proceed.
 * <p>
 * v1 blocks synchronously. When rejected, the node throws
 * {@link WorkflowException.ApprovalRejectedException}: with an onError
 * edge declared the rejection routes to a rejection handler, otherwise
 * the workflow FAILS (design decision D6 - Stage 6 will make this
 * pause/resumable without changing graph definitions).
 * <p>
 * On approval the node passes its input through unchanged, so downstream
 * nodes receive the payload.
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
    public NodeResult execute(NodeContext ctx) {
        boolean approved = approvalService.approve(
                new ApprovalService.Request(id, summary, ctx.input()));
        if (!approved) {
            throw new WorkflowException.ApprovalRejectedException(id);
        }
        // Pass the payload through to the next node
        return NodeResult.of(ctx.input());
    }
}

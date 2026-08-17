package io.github.qwzhang01.agent.workflow;

/**
 * Pluggable human-approval backend used by HumanApprovalNode.
 * <p>
 * Design decision (D6): v1 blocks synchronously (mock / console).
 * Stage 6 Checkpoint will only swap the implementation
 * (pause -> persist -> resume); the graph definition stays unchanged.
 */
public interface ApprovalService {

    /**
     * Ask for an approval decision.
     *
     * @return true to approve (workflow continues), false to reject
     * (node throws ApprovalRejectedException)
     */
    boolean approve(Request request);

    /**
     * Approval request payload.
     *
     * @param nodeId  node asking for approval
     * @param summary what the approver is being asked to approve
     * @param payload workflow input / business data for the approver to inspect
     */
    record Request(String nodeId, String summary, Object payload) {
    }
}

package io.github.qwzhang01.agent.workflow;

/**
 * Pluggable human-approval backend used by HumanApprovalNode.
 * <p>
 * Two modes:
 * <p>
 * <b>Synchronous (Stage 5)</b>: {@link #approve} blocks until a decision
 * is made. Used when calling GraphRuntime.run() directly (no RunManager).
 * <p>
 * <b>Asynchronous (Stage 6)</b>: {@link #requestApproval} sends the request
 * without waiting, the node throws PauseException, and on resume
 * {@link #checkDecision} returns the result. Used via RunManager for
 * pause-resume workflows.
 */
public interface ApprovalService {

    /**
     * Synchronous approval: blocks until a decision is made.
     *
     * @return true to approve, false to reject
     */
    boolean approve(Request request);

    /**
     * Asynchronous: send the approval request without waiting.
     * The node will throw PauseException after this returns.
     *
     * @param runId   the Run requesting approval
     * @param nodeId  the node requesting approval
     * @param summary what the approver is being asked to approve
     * @param payload business data for the approver to inspect
     */
    default void requestApproval(String runId, String nodeId, String summary, Object payload) {
        throw new UnsupportedOperationException("Async approval not supported by this service");
    }

    /**
     * Asynchronous: check whether a decision has been made.
     * Called on resume (when the paused node is re-executed).
     *
     * @return Boolean.TRUE (approved), Boolean.FALSE (rejected), or null (pending)
     */
    default Boolean checkDecision(String runId, String nodeId) {
        throw new UnsupportedOperationException("Async approval not supported by this service");
    }

    /**
     * Approval request payload (for synchronous mode).
     *
     * @param nodeId  node asking for approval
     * @param summary what the approver is being asked to approve
     * @param payload workflow input / business data for the approver to inspect
     */
    record Request(String nodeId, String summary, Object payload) {}
}

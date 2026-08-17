package io.github.qwzhang01.agent.workflow;

/**
 * Workflow-level exception: definition errors and runtime routing errors.
 * <p>
 * Definition errors (duplicate id, unknown edge endpoint) are thrown at
 * build time - fail fast. Routing errors (dead end, ambiguous routing)
 * surface as ExecutionResult.FAILED at run time.
 */
public class WorkflowException extends RuntimeException {

    public WorkflowException(String message) {
        super(message);
    }

    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Thrown by HumanApprovalNode when the approver rejects the request.
     * Behaves like a node failure: onError edge if present, else workflow FAILED.
     */
    public static final class ApprovalRejectedException extends WorkflowException {
        public ApprovalRejectedException(String nodeId) {
            super("Approval rejected at node '" + nodeId + "'");
        }
    }
}

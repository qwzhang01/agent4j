package io.github.qwzhang01.agent.workflow.runtime;

/**
 * Thrown by a node to request the runtime to pause execution.
 * <p>
 * Design decision (D3): pause is node-initiated, not runtime-polled.
 * The node does its side effect (e.g. send approval request), then throws
 * this exception. The runtime catches it, saves a checkpoint, and returns
 * a PAUSED result. On resume, the same node is re-executed with
 * {@code ctx.isResuming() == true} so it can take the resume path.
 * <p>
 * PauseException is NOT retried by RetryPolicy (it's not a failure).
 */
public class PauseException extends Exception {

    private final String nodeId;

    public PauseException(String nodeId, String reason) {
        super(reason);
        this.nodeId = nodeId;
    }

    /** The node that requested the pause. On resume, execution restarts here. */
    public String nodeId() {
        return nodeId;
    }
}

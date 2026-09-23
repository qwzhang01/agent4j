package io.github.qwzhang01.agent.workflow.runtime;

/**
 * Lifecycle state of a single workflow execution (a Run).
 * <p>
 * Transitions:
 * <pre>{@code
 * RUNNING -> SUCCEEDED (reached END)
 * RUNNING -> FAILED (node error, no onError edge)
 * RUNNING -> PAUSED (node threw PauseException)
 * RUNNING -> CANCELLED (caller called cancel)
 * RUNNING -> WAITING_APPROVAL durable approval pending)
 * WAITING_APPROVAL -> RUNNING (approval landed APPROVED)
 * WAITING_APPROVAL -> FAILED (approval REJECTED / EXPIRED / REVOKED)
 * PAUSED -> RUNNING (resume)
 * }</pre>
 * Terminal states: SUCCEEDED, FAILED, CANCELLED.
 */
public enum RunState {
    RUNNING,
    PAUSED,
    WAITING_APPROVAL,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }

    /** Resumable: a live worker may pick the run back up from here. */
    public boolean isResumable() {
        return this == PAUSED || this == WAITING_APPROVAL;
    }
}

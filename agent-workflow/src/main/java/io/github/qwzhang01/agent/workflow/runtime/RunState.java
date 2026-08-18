package io.github.qwzhang01.agent.workflow.runtime;

/**
 * Lifecycle state of a single workflow execution (a Run).
 * <p>
 * Transitions:
 * <pre>{@code
 * RUNNING -> SUCCEEDED     (reached END)
 * RUNNING -> FAILED        (node error, no onError edge)
 * RUNNING -> PAUSED        (node threw PauseException)
 * RUNNING -> CANCELLED     (caller called cancel())
 * PAUSED  -> RUNNING       (resume)
 * }</pre>
 * Terminal states: SUCCEEDED, FAILED, CANCELLED.
 */
public enum RunState {
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}

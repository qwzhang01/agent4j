package io.github.qwzhang01.agent.scheduler;

/**
 * Lifecycle state of an async task.
 * <pre>{@code
 * PENDING -> RUNNING -> SUCCEEDED
 *                  \-> FAILED
 *                  \-> WAITING_EVENT -> RUNNING (event fired)
 *                  \-> WAITING_HUMAN -> RUNNING (human acted)
 * Any state -> CANCELLED
 * }</pre>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    WAITING_EVENT,
    WAITING_HUMAN,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}

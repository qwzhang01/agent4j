package io.github.qwzhang01.agent.workflow.runtime.durable;

/**
 * Optimistic-lock violation on {@link RunStore#update} (Stage 3.3): two
 * workers raced on the same run, or a stale in-memory copy was written
 * back. Recovery: reload the row, re-apply the transition, retry once.
 */
public class VersionConflictException extends RuntimeException {

    private final RunRecord stored;

    public VersionConflictException(String message, RunRecord stored) {
        super(message);
        this.stored = stored;
    }

    /** The winning row that caused this write to lose. */
    public RunRecord storedRecord() {
        return stored;
    }
}

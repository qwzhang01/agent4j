package io.github.qwzhang01.agent.core.run;

/**
 * Read-only cancellation view (Stage 1.4).
 * <p>
 * Handed to every execution boundary (model call, tool execution,
 * workflow node, sandbox run) via {@link RunContext}. Boundaries check
 * at natural points; a hit must surface as {@link RunCancelledException},
 * never as a generic business error.
 */
public interface CancellationToken {

    /** Whether cancellation was requested for this run. */
    boolean isCancelled();

    /**
     * Throw {@link RunCancelledException} if cancellation was requested.
     * The structured way for a boundary to bail out early.
     */
    void check();
}

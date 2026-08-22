package io.github.qwzhang01.agent.orchestrator;

import java.util.Objects;

/**
 * Failure semantics for a dispatch (Stage 11 M11.3, D4).
 * <p>
 * Two orthogonal decisions packed together:
 * <ul>
 *   <li>{@link Mode} -- what ONE failed task does to the OTHERS:
 *       <ul>
 *         <li>{@code FAIL_FAST} -- cancel the rest, the whole dispatch failed
 *             (e.g. payment steps: no point charging the card if validation failed)</li>
 *         <li>{@code BEST_EFFORT} -- the rest keep running, failures are reported
 *             as data (e.g. multi-source retrieval: one mirror down is fine)</li>
 *       </ul>
 *   <li>{@code retryBackoffMs} -- pause between retry attempts (orchestration-wide
 *       pacing; the retry BUDGET is per-task: {@code WorkerTask.maxRetries}).</li>
 * </ul>
 * Retry budget lives on the task (the caller knows whether THIS task is worth
 * retrying); backoff pacing lives here (the supervisor throttles globally).
 *
 * @param mode           failure mode
 * @param retryBackoffMs pause between attempts, >= 0 (0 = retry immediately)
 */
public record FailurePolicy(Mode mode, long retryBackoffMs) {

    public FailurePolicy {
        Objects.requireNonNull(mode, "mode must not be null");
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs must be >= 0");
        }
    }

    public enum Mode {
        /** One final failure cancels all remaining tasks; dispatch fails. */
        FAIL_FAST,
        /** Failures are isolated; remaining tasks run to completion. */
        BEST_EFFORT
    }

    /** No retry, no cancellation: failures are reported as data (M11.2 behavior). */
    public static FailurePolicy bestEffort() {
        return new FailurePolicy(Mode.BEST_EFFORT, 0);
    }

    /** BEST_EFFORT with a pause between retry attempts. */
    public static FailurePolicy bestEffort(long retryBackoffMs) {
        return new FailurePolicy(Mode.BEST_EFFORT, retryBackoffMs);
    }

    /** Any final failure cancels everything still running. */
    public static FailurePolicy failFast() {
        return new FailurePolicy(Mode.FAIL_FAST, 0);
    }
}

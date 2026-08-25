package io.github.qwzhang01.agent.workflow.runtime;

import java.time.Duration;
import java.util.Objects;

/**
 * Per-run timeout policy: a cap on one node, and a cap on one execute attempt.
 * <p>
 * {@link Duration#ZERO} (or {@link #none()}) means unlimited on that axis.
 * Negative durations are rejected at construction.
 * <p>
 * Run timeout is measured from the start of the current
 * {@code GraphRuntime.execute} call, not from the original {@link Run}
 * creation. Pause time (hours waiting for a human) does not count —
 * otherwise every approval flow would time out.
 * <p>
 * Node timeout is a hard wait around {@code node.execute}. The worker may
 * keep running after the wait expires (cooperative, same limit as cancel).
 *
 * @param nodeTimeout max wait for a single node; {@link Duration#ZERO} = none
 * @param runTimeout  max wall time of one execute attempt; {@link Duration#ZERO} = none
 */
public record TimeoutPolicy(Duration nodeTimeout, Duration runTimeout) {

    public static final TimeoutPolicy NONE = new TimeoutPolicy(Duration.ZERO, Duration.ZERO);

    public TimeoutPolicy {
        Objects.requireNonNull(nodeTimeout, "nodeTimeout");
        Objects.requireNonNull(runTimeout, "runTimeout");
        if (nodeTimeout.isNegative() || runTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be >= 0 (0 = unlimited)");
        }
    }

    public static TimeoutPolicy none() {
        return NONE;
    }

    /** Run-level cap only. */
    public static TimeoutPolicy runOnly(Duration runTimeout) {
        return new TimeoutPolicy(Duration.ZERO, runTimeout);
    }

    /** Both axes. */
    public static TimeoutPolicy of(Duration nodeTimeout, Duration runTimeout) {
        return new TimeoutPolicy(nodeTimeout, runTimeout);
    }

    public long nodeTimeoutMs() {
        return nodeTimeout.isZero() ? 0L : nodeTimeout.toMillis();
    }

    public long runTimeoutMs() {
        return runTimeout.isZero() ? 0L : runTimeout.toMillis();
    }

    /**
     * @param executeStartedMs {@code System.currentTimeMillis()} at the start
     *                         of this execute attempt
     */
    public boolean isRunTimedOut(long executeStartedMs) {
        long limit = runTimeoutMs();
        return limit > 0 && System.currentTimeMillis() - executeStartedMs >= limit;
    }
}

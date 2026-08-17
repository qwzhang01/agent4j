package io.github.qwzhang01.agent.workflow;

/**
 * Node-level retry policy, mirroring RetryModelClient semantics
 * at the workflow layer (design decision D7: same governance,
 * consistent behavior across layers).
 *
 * @param maxRetries       retries after the first attempt (total attempts = 1 + maxRetries)
 * @param initialBackoffMs delay before the first retry; 0 = immediate
 * @param multiplier       backoff multiplier between retries (1.0 = fixed delay)
 */
public record RetryPolicy(int maxRetries, long initialBackoffMs, double multiplier) {

    /**
     * No retries - single attempt.
     */
    public static final RetryPolicy NONE = new RetryPolicy(0, 0, 1.0);

    public static RetryPolicy fixed(int maxRetries, long delayMs) {
        return new RetryPolicy(maxRetries, delayMs, 1.0);
    }

    public static RetryPolicy backoff(int maxRetries, long initialDelayMs) {
        return new RetryPolicy(maxRetries, initialDelayMs, 2.0);
    }

    /**
     * Delay before the retry that follows the given zero-based attempt index.
     */
    public long delayForAttempt(int attemptIndex) {
        if (initialBackoffMs <= 0) {
            return 0;
        }
        return (long) (initialBackoffMs * Math.pow(multiplier, attemptIndex));
    }
}

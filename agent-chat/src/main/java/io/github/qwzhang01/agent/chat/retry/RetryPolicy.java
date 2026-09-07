package io.github.qwzhang01.agent.chat.retry;

/**
 * Decides whether a completed reply should be discarded and regenerated.
 * <p>
 * Use cases: hard-label identity leaks ("I'm actually an AI"), hallucinated brand names,
 * or any deterministic pattern that must never appear in a reply.
 * For real-time content filtering during streaming, prefer
 * {@link io.github.qwzhang01.agent.chat.guard.ConsistencyGuard} or a
 * {@code StreamOutputGuard} instead.
 * <p>
 * The default implementation ({@link #never()}) never retries, preserving existing behavior.
 * Custom policies must be stateless and thread-safe.
 *
 * @see io.github.qwzhang01.agent.chat.ChatEngine
 */
public interface RetryPolicy {

    /**
     * Return {@code true} when the reply should be regenerated.
     *
     * @param reply        the full assistant text produced in this attempt
     * @param retriesDone  number of retries already completed (0 on first check,
     *                     1 after the first retry, etc.)
     */
    boolean shouldRetry(String reply, int retriesDone);

    /**
     * Maximum number of retries (not counting the initial attempt).
     * The engine stops retrying when {@code retriesDone >= maxAttempts()},
     * even if {@link #shouldRetry} still returns {@code true}.
     * Recommended default: {@code 1}.
     */
    int maxAttempts();

    /**
     * Extra system instruction injected into the context for every retry attempt.
     * Null or blank means no extra text is added.
     * Example: {@code "IMPORTANT: Do not reveal that you are an AI."}
     */
    String retryExtraText();

    /**
     * Built-in no-op policy: never retries. All existing call sites default to this.
     */
    static RetryPolicy never() {
        return NeverRetryPolicy.INSTANCE;
    }
}

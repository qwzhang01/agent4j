package io.github.qwzhang01.agent.chat.retry;

/**
 * Default no-op implementation. Always accepted on the first attempt; zero retries.
 * Returned by {@link RetryPolicy#never()}.
 */
final class NeverRetryPolicy implements RetryPolicy {

    static final NeverRetryPolicy INSTANCE = new NeverRetryPolicy();

    private NeverRetryPolicy() {
    }

    @Override
    public boolean shouldRetry(String reply, int retriesDone) {
        return false;
    }

    @Override
    public int maxAttempts() {
        return 0;
    }

    @Override
    public String retryExtraText() {
        return null;
    }
}

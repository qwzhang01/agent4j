package io.github.qwzhang01.agent.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Token budget tracker for cost control.
 * <p>
 * Design decision (D6): v1 is a simple per-Run cumulative counter. If the
 * total exceeds the limit, the Run should be FAILED. Complex per-user /
 * per-model / time-window limiting is Stage 18 scope.
 */
public class TokenBudget {

    private final long limit;
    private final AtomicLong used = new AtomicLong();

    public TokenBudget(long limit) {
        this.limit = limit;
    }

    /** Record token usage. Returns true if still within budget. */
    public boolean consume(long tokens) {
        long now = used.addAndGet(tokens);
        return now <= limit;
    }

    public long used() {
        return used.get();
    }

    public long limit() {
        return limit;
    }

    public boolean isExceeded() {
        return used.get() > limit;
    }

    public long remaining() {
        return Math.max(0, limit - used.get());
    }
}

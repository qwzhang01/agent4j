package io.github.qwzhang01.agent.security;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple count-based rate limiter (Stage 9 D7 v1).
 * <p>
 * Each tool gets a fixed window of N calls per minute. When the window
 * expires, the counter resets. This is the simplest correct rate limiter -
 * not smooth (bursty at window boundaries), but sufficient for demonstrating
 * the governance mechanism.
 * <p>
 * Full implementations (token bucket / sliding window / per-user) are Stage 18.
 */
public class SimpleRateLimiter implements RateLimiter {

    private final int maxCallsPerMinute;
    private final long windowMs;
    private final ConcurrentHashMap<String, WindowCounter> counters = new ConcurrentHashMap<>();

    /**
     * @param maxCallsPerMinute maximum calls allowed per tool per window
     */
    public SimpleRateLimiter(int maxCallsPerMinute) {
        this(maxCallsPerMinute, 60_000);
    }

    /**
     * @param maxCalls  maximum calls per window
     * @param windowMs  window size in milliseconds (default 60000 = 1 minute)
     */
    public SimpleRateLimiter(int maxCalls, long windowMs) {
        this.maxCallsPerMinute = maxCalls;
        this.windowMs = windowMs;
    }

    @Override
    public boolean tryAcquire(String toolName) {
        WindowCounter counter = counters.computeIfAbsent(toolName, k -> new WindowCounter());
        return counter.tryAcquire(maxCallsPerMinute, windowMs);
    }

    /**
     * Reset the counter for a tool (for testing).
     */
    public void reset(String toolName) {
        counters.remove(toolName);
    }

    /**
     * Reset all counters (for testing).
     */
    public void resetAll() {
        counters.clear();
    }

    // ============ Inner ============

    private static class WindowCounter {
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        synchronized boolean tryAcquire(int max, long windowMs) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= windowMs) {
                // Window expired -> reset
                windowStart = now;
                count.set(0);
            }
            return count.getAndIncrement() < max;
        }
    }
}

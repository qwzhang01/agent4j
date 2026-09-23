package io.github.qwzhang01.agent.security;

/**
 * Rate limiter for tool calls (D7 - optional component).
 * <p>
 * v1 = simple count-based window (N calls per minute per tool).
 * Full implementations (token bucket / sliding window / per-user) are .
 */
public interface RateLimiter {

    /**
     * Try to acquire a permit for the given tool.
     *
     * @param toolName the tool requesting a call
     * @return true if allowed, false if rate limit exceeded
     */
    boolean tryAcquire(String toolName);

    /**
     * Give back a permit previously taken by {@link #tryAcquire}. Default is
     * a no-op so hosts with one-way limiters stay compatible. Used when a
     * REQUIRES_APPROVAL call parks as PENDING — the wait must not spend the
     * quota that the later approved execute still needs.
     */
    default void release(String toolName) {
    }
}

package io.github.qwzhang01.agent.security;

/**
 * Rate limiter for tool calls (Stage 9 D7 - optional component).
 * <p>
 * v1 = simple count-based window (N calls per minute per tool).
 * Full implementations (token bucket / sliding window / per-user) are Stage 18.
 */
public interface RateLimiter {

    /**
     * Try to acquire a permit for the given tool.
     *
     * @param toolName the tool requesting a call
     * @return true if allowed, false if rate limit exceeded
     */
    boolean tryAcquire(String toolName);
}

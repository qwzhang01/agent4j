package io.github.qwzhang01.agent.memory;

/**
 * Lifecycle status of a memory entry.
 * <p>
 * Governance flow:
 * <pre>
 * ACTIVE          - live, retrievable, injected into context
 * PENDING_REVIEW  - written but awaiting admin approval (channel scope default)
 * REJECTED        - admin rejected, not retrievable
 * SUPERSEDED      - replaced by a newer entry with the same subject (kept for audit)
 * EXPIRED         - TTL passed, lazily filtered on retrieval
 * </pre>
 */
public enum MemoryStatus {
    ACTIVE,
    PENDING_REVIEW,
    REJECTED,
    SUPERSEDED,
    EXPIRED
}

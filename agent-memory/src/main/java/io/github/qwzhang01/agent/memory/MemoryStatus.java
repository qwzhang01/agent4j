package io.github.qwzhang01.agent.memory;

/**
 * Lifecycle status of a memory entry.
 * <p>
 * Governance flow:
 * <pre>
 * ACTIVE          - live, retrievable, injected into context
 * PENDING_REVIEW  - written but awaiting admin approval (channel scope default)
 * REJECTED        - admin rejected, not retrievable
 * SUPERSEDED      - replaced because the old content was wrong from the start
 *                   (lifecycle=CONFLICT); audit-only, never returned by queries
 * HISTORICAL      - replaced because the old content was once true but changed
 *                   (lifecycle=EVOLVE, e.g. "I moved to Shanghai" replaces "lives in Shenzhen");
 *                   excluded from the default context, visible to explicit history queries
 * EXPIRED         - TTL passed, lazily filtered on retrieval
 * </pre>
 */
public enum MemoryStatus {
    ACTIVE,
    PENDING_REVIEW,
    REJECTED,
    SUPERSEDED,
    HISTORICAL,
    EXPIRED
}

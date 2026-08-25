package io.github.qwzhang01.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for long-term memory entries.
 * <p>
 * Implementations must enforce scope isolation: {@link #query} only returns
 * entries whose scope is in the query's scope list.
 * <p>
 * v1 implementation: {@link io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore}. The interface is designed so
 * a persistent backend (JSONL / DB / Redis) can be added later without changing
 * callers.
 */
public interface MemoryStore {

    /**
     * Write a new entry. The entry's id should be assigned by the store if null.
     */
    MemoryEntry write(MemoryEntry entry);

    /**
     * Query entries matching the criteria. Only ACTIVE (non-expired, non-pending)
     * entries are returned unless the query explicitly opts into other statuses
     * via the admin path.
     */
    List<MemoryEntry> query(MemoryQuery query);

    /**
     * Find the currently-active entry for a given scope + subject.
     * Used by conflict detection / supersede logic.
     */
    Optional<MemoryEntry> findActiveBySubject(String scope, String subject);

    /**
     * Update an existing entry (status transition, content edit, supersede).
     */
    MemoryEntry update(MemoryEntry entry);

    /**
     * Find an entry by id (any status).
     */
    Optional<MemoryEntry> findById(String id);

    /**
     * Delete an entry by id. Returns true if it existed.
     * Note: governance prefers supersede over physical delete; this is for
     * hard removal (e.g. GDPR / admin purge).
     */
    boolean delete(String id);

    /**
     * List all entries in a scope (any status). Used by the admin governance view.
     */
    List<MemoryEntry> listByScope(String scope);
}

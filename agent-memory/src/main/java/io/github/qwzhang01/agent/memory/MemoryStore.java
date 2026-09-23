package io.github.qwzhang01.agent.memory;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for long-term memory entries.
 * <p>
 * Implementations must enforce scope isolation: {@link #query} only returns
 * entries whose scope is in the query's scope list.
 * <p>
 * v1 implementation: {@link io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore};
 * memory roadmap step 4 adds the persistent PostgreSQL ledger
 * {@link io.github.qwzhang01.agent.memory.store.PgMemoryStore}. The interface is designed so
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
     * The supersede ledger move: close {@code closedOld} and write {@code newEntry}
     * as its replacement in one logical step (memory roadmap step 4).
     * <p>
     * Callers pass the old entry already in its closed form — stamped by
     * {@link MemoryEntry#closedAs} (status + both time axes) or
     * {@link MemoryEntry#withStatus}. The default implementation is sequential:
     * {@link #update} the closed line, then {@link #write} the replacement.
     * Persistent implementations override this with a single transaction
     * (see {@link io.github.qwzhang01.agent.memory.store.PgMemoryStore}), so a
     * failure midway — e.g. a unique-index violation when a racing writer
     * already holds the ACTIVE slot — rolls the close back: the ledger never
     * ends up with two ACTIVE lines for one subject, nor with zero.
     * <p>
     * Ordering contract: the old line is closed <b>before</b> the replacement
     * is written; implementations backed by a partial unique index on
     * {@code (scope, subject) WHERE status = 'ACTIVE'} rely on this order.
     *
     * @param closedOld the old entry in its already-closed form
     * @param newEntry the replacement; the id / createdAt defaults of {@link #write} apply
     * @return the stored replacement (store-assigned id when the input had none)
     */
    default MemoryEntry supersede(MemoryEntry closedOld, MemoryEntry newEntry) {
        update(closedOld);
        return write(newEntry);
    }

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

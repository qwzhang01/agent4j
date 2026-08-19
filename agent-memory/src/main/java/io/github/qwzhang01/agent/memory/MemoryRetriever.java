package io.github.qwzhang01.agent.memory;

import java.util.List;

/**
 * Read-side of the memory pipeline (Stage 8).
 * <p>
 * Retrieves ACTIVE memories from the store, bounded by the given scopes.
 * Only ACTIVE (non-expired, non-pending) entries are returned - the store
 * enforces this.
 */
public class MemoryRetriever {

    private final MemoryStore store;

    public MemoryRetriever(MemoryStore store) {
        this.store = store;
    }

    /**
     * Recall all active memories visible from the given scopes.
     */
    public List<MemoryEntry> recall(List<String> scopes) {
        return store.query(MemoryQuery.builder().scopes(scopes).build());
    }

    /**
     * Recall memories of a specific type.
     */
    public List<MemoryEntry> recall(List<String> scopes, MemoryType type) {
        return store.query(MemoryQuery.builder().scopes(scopes).type(type).build());
    }

    /**
     * Recall memories matching a keyword (case-insensitive content match).
     */
    public List<MemoryEntry> recallByKeyword(List<String> scopes, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return recall(scopes);
        }
        return store.query(MemoryQuery.builder().scopes(scopes).keyword(keyword).build());
    }

    /**
     * Recall the most relevant memories for the current context.
     * v1: returns all active memories in scope (no semantic ranking).
     */
    public List<MemoryEntry> recallForContext(List<String> scopes, int limit) {
        return store.query(MemoryQuery.builder().scopes(scopes).limit(limit).build());
    }
}

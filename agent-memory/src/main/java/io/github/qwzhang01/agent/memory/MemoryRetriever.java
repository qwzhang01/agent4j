package io.github.qwzhang01.agent.memory;

import java.util.Comparator;
import java.util.List;

/**
 * Read-side of the memory pipeline (Stage 8).
 * <p>
 * Retrieves ACTIVE memories from the store, bounded by the given scopes.
 * Only ACTIVE (non-expired, non-pending) entries are returned - the store
 * enforces this.
 */
public class MemoryRetriever {

    /**
     * Context recall rank: higher importance first; same score keeps newer entries.
     * Hosts that want "user-edited first" raise those entries' importance at write time.
     */
    private static final Comparator<MemoryEntry> BY_IMPORTANCE_THEN_RECENCY =
            Comparator.comparingDouble(MemoryEntry::importance).reversed()
                    .thenComparing(MemoryEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()));

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
     * Recall the most important memories for the current context.
     * Ranks the full in-scope ACTIVE set by {@link MemoryEntry#importance()}
     * (then recency), then keeps the top {@code limit} entries.
     * {@code limit <= 0} means no cut-off.
     */
    public List<MemoryEntry> recallForContext(List<String> scopes, int limit) {
        List<MemoryEntry> ranked = store.query(MemoryQuery.builder().scopes(scopes).build())
                .stream()
                .sorted(BY_IMPORTANCE_THEN_RECENCY)
                .toList();
        if (limit <= 0 || ranked.size() <= limit) {
            return ranked;
        }
        return List.copyOf(ranked.subList(0, limit));
    }
}

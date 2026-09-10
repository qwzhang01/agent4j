package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.memory.ranking.ImportanceRankingStrategy;
import io.github.qwzhang01.agent.memory.ranking.RankingStrategy;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-side of the memory pipeline (Stage 8).
 *
 * <p>Retrieves ACTIVE memories from the store, bounded by the given scopes.
 * Only ACTIVE (non-expired, non-pending) entries are returned — the store
 * enforces this invariant.
 *
 * <p>The ranking algorithm is pluggable via {@link RankingStrategy}.  The default
 * strategy ({@link ImportanceRankingStrategy}) preserves the token-overlap +
 * importance-weighted behaviour that existed before A8; no call site changes are
 * required for existing code.
 *
 * <p>Custom strategies (e.g. embedding-cosine hybrid) can be injected through the
 * two-argument constructor:
 * <pre>{@code
 * MemoryRetriever retriever = new MemoryRetriever(store, new HybridRankingStrategy());
 * }</pre>
 *
 * <p>Subclasses may override {@link #recallForContext(List, int, String)} to apply
 * additional post-ranking filters (see {@code MoonlitChatMemoryRetriever}).
 */
public class MemoryRetriever {

    /**
     * Used exclusively by {@link #recallSummaries}: summaries are global digests
     * that are not query-specific, so they always rank by importance then recency.
     */
    private static final Comparator<MemoryEntry> BY_IMPORTANCE_THEN_RECENCY =
            Comparator.comparingDouble(MemoryEntry::importance).reversed()
                    .thenComparing(MemoryEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private final MemoryStore store;
    private final RankingStrategy strategy;

    /**
     * Constructs a retriever with the default {@link ImportanceRankingStrategy}.
     */
    public MemoryRetriever(MemoryStore store) {
        this(store, new ImportanceRankingStrategy());
    }

    /**
     * Constructs a retriever with a custom ranking strategy.
     *
     * @param store    the memory store to query (must not be null)
     * @param strategy the ranking strategy applied in {@link #recallForContext} (must not be null)
     */
    public MemoryRetriever(MemoryStore store, RankingStrategy strategy) {
        this.store = Objects.requireNonNull(store, "store");
        this.strategy = Objects.requireNonNull(strategy, "strategy");
    }

    // ============ Public Recall API ============

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
     * Recall all SUMMARY entries for the given scopes, ranked by importance then recency.
     *
     * <p>Summaries are produced by the context compressor and represent a digest of prior
     * conversation.  They occupy a dedicated slot in context assembly (see
     * {@link io.github.qwzhang01.agent.chat.context.MemorySource}) so that high-importance
     * summaries never crowd out specific FACT / EPISODE / PREFERENCE entries.
     *
     * <p>Summaries are intentionally <em>not</em> routed through {@link #strategy}: they are
     * global digests, not query-specific entries, so importance-then-recency is always correct.
     */
    public List<MemoryEntry> recallSummaries(List<String> scopes) {
        return store.query(MemoryQuery.builder().scopes(scopes).type(MemoryType.SUMMARY).build())
                .stream()
                .sorted(BY_IMPORTANCE_THEN_RECENCY)
                .toList();
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
     * Recall the currently-visible entries for an exact subject (ACTIVE only).
     * Scope isolation applies as everywhere else.
     */
    public List<MemoryEntry> recallBySubject(List<String> scopes, String subject) {
        return store.query(MemoryQuery.builder().scopes(scopes).subject(subject).build());
    }

    /**
     * Recall the full timeline of a subject: the current ACTIVE entry plus
     * every HISTORICAL predecessor, newest first. SUPERSEDED entries (old
     * content that was wrong from the start) are never included.
     * <p>
     * Backs the {@code include_history} mode of {@code search_memory}: answering
     * "where did I live before?" without polluting the default context.
     */
    public List<MemoryEntry> recallSubjectHistory(List<String> scopes, String subject) {
        return store.query(MemoryQuery.builder()
                .scopes(scopes)
                .subject(subject)
                .statuses(MemoryStatus.ACTIVE, MemoryStatus.HISTORICAL)
                .build());
    }

    /**
     * Recall the most important memories for the current context.
     * Delegates to {@link #recallForContext(List, int, String)} with no query.
     *
     * @param scopes visible memory scopes
     * @param limit  max entries; {@code <= 0} means no cut-off
     */
    public List<MemoryEntry> recallForContext(List<String> scopes, int limit) {
        return recallForContext(scopes, limit, null);
    }

    /**
     * Recall the most important memories for the current context, biased toward
     * entries relevant to {@code query}.
     *
     * <p>The ranking is fully delegated to the injected {@link RankingStrategy}.
     * The default strategy ({@link ImportanceRankingStrategy}) applies:
     * <pre>
     *   score(e) = e.importance() + QUERY_BOOST_WEIGHT * tokenOverlap(e, query)
     * </pre>
     * When {@code query} is {@code null} or blank the strategy degrades gracefully
     * to importance-then-recency, preserving backward compatibility with all
     * existing 2-arg callers.
     *
     * @param scopes visible memory scopes
     * @param limit  max entries; {@code <= 0} means no cut-off
     * @param query  optional free-text hint (e.g. current user message); {@code null} = no boost
     */
    public List<MemoryEntry> recallForContext(List<String> scopes, int limit, String query) {
        List<MemoryEntry> all = store.query(MemoryQuery.builder().scopes(scopes).build());
        List<MemoryEntry> ranked = strategy.rank(all, query);
        if (limit <= 0 || ranked.size() <= limit) {
            return ranked;
        }
        return List.copyOf(ranked.subList(0, limit));
    }
}

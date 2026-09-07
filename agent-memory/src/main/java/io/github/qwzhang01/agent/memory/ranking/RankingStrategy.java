package io.github.qwzhang01.agent.memory.ranking;

import io.github.qwzhang01.agent.memory.MemoryEntry;

import java.util.List;

/**
 * Pluggable ranking strategy for context recall.
 *
 * <p>Implementations receive the full list of candidate {@link MemoryEntry} objects
 * (already filtered by scope and status) and must return them in preference order:
 * index 0 is the highest-priority entry that should appear in the LLM context window.
 *
 * <p>Two built-in implementations are provided:
 * <ul>
 *   <li>{@link ImportanceRankingStrategy} – token-overlap + importance weighted sum
 *       (the default behaviour preserved from {@code MemoryRetriever} pre-A8).</li>
 *   <li>{@link HybridRankingStrategy} – stub for future embedding-cosine fusion;
 *       currently delegates to {@link ImportanceRankingStrategy}.</li>
 * </ul>
 *
 * <p>Typical usage via {@code MemoryRetriever}:
 * <pre>{@code
 * MemoryRetriever retriever = new MemoryRetriever(store, new HybridRankingStrategy());
 * }</pre>
 */
public interface RankingStrategy {

    /**
     * Rank candidate memory entries for injection into the model context.
     *
     * @param candidates unsorted active memory entries from the store
     * @param query      optional free-text user message (e.g. current turn text);
     *                   {@code null} or blank means no query bias
     * @return ranked list – first element has highest priority
     */
    List<MemoryEntry> rank(List<MemoryEntry> candidates, String query);

    /**
     * Returns the default importance + recency ranking strategy.
     */
    static RankingStrategy defaults() {
        return new ImportanceRankingStrategy();
    }
}

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
 * <p>Two implementations ship with this module, at different maturity levels:
 * <ul>
 *   <li>{@link ImportanceRankingStrategy} – <b>production-ready</b>: token-overlap +
 *       importance weighted sum (the default behaviour preserved from
 *       {@code MemoryRetriever} pre-A8).</li>
 *   <li>{@link HybridRankingStrategy} – <b>not yet implemented</b>: a placeholder that
 *       currently delegates 100% of its ranking to {@link ImportanceRankingStrategy}.
 *       Constructing it today has no behavioral effect beyond using the default
 *       strategy directly; do not depend on embedding-based ranking until its
 *       Javadoc says otherwise.</li>
 * </ul>
 *
 * <p>Typical usage via {@code MemoryRetriever} (production default; equivalent to the
 * 1-arg constructor):
 * <pre>{@code
 * MemoryRetriever retriever = new MemoryRetriever(store, new ImportanceRankingStrategy());
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

package io.github.qwzhang01.agent.memory.ranking;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Vector + token hybrid ranking strategy (read-side embedding, step 1 of the
 * memory roadmap; fulfils the design promised by this class's former stub).
 *
 * <p>Effective sort key when a query and query vector are available:
 * <pre>
 *   score(e) = α * cosineSim(queryVec, e.embedding)      // semantic path
 *            + β * tokenOverlap(e, query)                // lexical path
 *            + γ * e.importance                          // governance path
 * </pre>
 * Default weights α = 0.5, β = 0.3, γ = 0.2 put semantics first, keep a lexical
 * floor for exact-key lookups, and let governance importance break ties.
 *
 * <p>Degradation is explicit and graceful, never a crash:
 * <ul>
 *   <li>No query ({@code null}/blank) — pure importance-then-recency, identical
 *       to {@link ImportanceRankingStrategy}.</li>
 *   <li>Entry without embedding (legacy data, or the embedding provider was
 *       down at write time) — that entry scores through the lexical +
 *       importance paths only. It can still surface, just never through the
 *       semantic door.</li>
 *   <li>Embedding call for the query itself fails — the whole ranking falls
 *       back to lexical + importance for that turn (logged as warn). One bad
 *       turn must not break the chat loop.</li>
 * </ul>
 *
 * <p>Vector dimension mismatch (provider swapped between write and read) is
 * treated as "no semantic signal" ({@link EmbeddingClient#cosineSimilarity}
 * returns 0.0), so swapping embedding models degrades rather than explodes.
 *
 * <p>Usage:
 * <pre>{@code
 * EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), embeddingClient);
 * MemoryRetriever retriever = new MemoryRetriever(store, new HybridRankingStrategy(embeddingClient));
 * }</pre>
 */
public final class HybridRankingStrategy implements RankingStrategy {

    private static final Logger log = LoggerFactory.getLogger(HybridRankingStrategy.class);

    /**
     * Default fusion weights: semantic 0.5, lexical 0.3, importance 0.2.
     */
    public static final double DEFAULT_SEMANTIC_WEIGHT = 0.5;
    public static final double DEFAULT_LEXICAL_WEIGHT = 0.3;
    public static final double DEFAULT_IMPORTANCE_WEIGHT = 0.2;

    private static final Comparator<MemoryEntry> BY_IMPORTANCE_THEN_RECENCY =
            Comparator.comparingDouble(MemoryEntry::importance).reversed()
                    .thenComparing(MemoryEntry::createdAt,
                            Comparator.nullsLast(Comparator.reverseOrder()));

    private final EmbeddingClient client;
    private final ImportanceRankingStrategy lexical;
    private final double semanticWeight;
    private final double lexicalWeight;
    private final double importanceWeight;

    /**
     * Default weights (0.5 / 0.3 / 0.2), lexical path reusing the token-overlap
     * logic of {@link ImportanceRankingStrategy}.
     */
    public HybridRankingStrategy(EmbeddingClient client) {
        this(client, DEFAULT_SEMANTIC_WEIGHT, DEFAULT_LEXICAL_WEIGHT, DEFAULT_IMPORTANCE_WEIGHT);
    }

    /**
     * @param client           embedding provider port used to embed the query
     * @param semanticWeight   α; clamped to [0, 1]
     * @param lexicalWeight    β; clamped to [0, 1]
     * @param importanceWeight γ; clamped to [0, 1]
     */
    public HybridRankingStrategy(EmbeddingClient client,
                                 double semanticWeight,
                                 double lexicalWeight,
                                 double importanceWeight) {
        this.client = Objects.requireNonNull(client, "client");
        this.lexical = new ImportanceRankingStrategy();
        this.semanticWeight = clamp(semanticWeight);
        this.lexicalWeight = clamp(lexicalWeight);
        this.importanceWeight = clamp(importanceWeight);
    }

    @Override
    public List<MemoryEntry> rank(List<MemoryEntry> candidates, String query) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        if (query == null || query.isBlank()) {
            return lexical.rank(candidates, null);
        }

        float[] queryVector = tryEmbedQuery(query);
        // Lexical relevance per entry, computed once; identical formula to the
        // default strategy (this is why we extend its logic instead of duplicating).
        // ImportanceRankingStrategy#queryRelevance is protected exactly for this.

        Comparator<MemoryEntry> comparator;
        if (queryVector == null) {
            // Semantic door closed this turn: lexical + importance only.
            comparator = Comparator.comparingDouble(
                            (MemoryEntry e) -> lexicalWeight * lexicalRelevance(e, query)
                                    + importanceWeight * e.importance())
                    .reversed()
                    .thenComparing(BY_IMPORTANCE_THEN_RECENCY);
        } else {
            comparator = Comparator.comparingDouble(
                            (MemoryEntry e) -> semanticWeight * EmbeddingClient.cosineSimilarity(
                                            queryVector, e.embedding())
                                    + lexicalWeight * lexicalRelevance(e, query)
                                    + importanceWeight * e.importance())
                    .reversed()
                    .thenComparing(BY_IMPORTANCE_THEN_RECENCY);
        }
        return candidates.stream().sorted(comparator).toList();
    }

    // ============ Internals ============

    /**
     * Lexical relevance via the default strategy's protected hook, so the two
     * strategies never drift apart on the token-overlap formula.
     */
    private double lexicalRelevance(MemoryEntry entry, String query) {
        return lexical.queryRelevance(entry, query);
    }

    /**
     * Embeds the query; any failure degrades this turn's ranking to lexical +
     * importance rather than throwing. Returns null on failure.
     */
    private float[] tryEmbedQuery(String query) {
        try {
            return client.embed(query);
        } catch (RuntimeException e) {
            log.warn("Query embedding failed; ranking degrades to lexical + importance: {}",
                    e.getMessage());
            return null;
        }
    }

    private static double clamp(double w) {
        return Math.max(0.0, Math.min(1.0, w));
    }
}

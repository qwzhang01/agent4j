package io.github.qwzhang01.agent.memory.ranking;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.client.ModelException.ErrorCode;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the vector + token hybrid ranking (read-side embedding step 1).
 * <p>
 * The flagship scenario is semantic drift: the query mentions "relocation"
 * while the stored memory says "lives in Shenzhen" — zero lexical overlap,
 * so only the semantic path (α) can surface it. All vectors are fixtures
 * wired through a stub {@link EmbeddingClient}; no live model is called.
 */
class HybridRankingStrategyTest {

    private static final Instant TS = Instant.parse("2026-09-10T00:00:00Z");

    /** Stub: fixed vector per text — the test IS the embedding model. */
    static final class VectorMapClient implements EmbeddingClient {
        final java.util.Map<String, float[]> map = new java.util.HashMap<>();

        VectorMapClient put(String text, float... vector) {
            map.put(text, vector);
            return this;
        }

        @Override
        public float[] embed(String text) {
            float[] v = map.get(text);
            if (v == null) {
                throw new ModelException(ErrorCode.MODEL_ERROR, "no vector for: " + text);
            }
            return v;
        }
    }

    // Flagship: semantic drift

    /**
     * Query "relocation plans" vs entries:
     * - "home-city: lives in Shenzhen"  (no lexical overlap; vector close to query)
     * - "work: next week interview"     (no overlap; vector orthogonal to query)
     * Shenzhen must rank first purely through the semantic path.
     */
    @Test
    void rank_semanticDrift_vectorBeatsLexicalVoid() {
        VectorMapClient client = new VectorMapClient()
                .put("relocation plans", 1.0f, 0.0f)
                .put("home-city: lives in Shenzhen", 0.95f, 0.1f)   // cosine ≈ 0.997
                .put("work: next week interview", 0.0f, 1.0f);      // cosine = 0
        HybridRankingStrategy strategy = new HybridRankingStrategy(client);

        List<MemoryEntry> ranked = strategy.rank(List.of(
                vecEntry("home-city", "lives in Shenzhen", 0.3, client),
                vecEntry("work", "next week interview", 0.9, client)),
                "relocation plans");

        assertEquals("lives in Shenzhen", ranked.get(0).content(),
                "semantic path must surface the zero-lexical-overlap entry");
        assertEquals("next week interview", ranked.get(1).content());
    }

    /**
     * Importance cannot beat a semantic + lexical double hit: with α=0.5 the
     * semantic gap (≈1.0 vs 0.0) dominates any importance delta (≤0.5 · 1.0).
     */
    @Test
    void rank_semanticPath_dominatesImportanceForUnrelatedHighScore() {
        VectorMapClient client = new VectorMapClient()
                .put("coffee order", 1.0f, 0.0f)
                .put("drink: latte with oat milk", 0.9f, 0.44f)     // cosine ≈ 0.898
                .put("tax: fiscal year deadline", 0.0f, 1.0f);
        HybridRankingStrategy strategy = new HybridRankingStrategy(client);

        List<MemoryEntry> ranked = strategy.rank(List.of(
                vecEntry("drink", "latte with oat milk", 0.2, client),
                vecEntry("tax", "fiscal year deadline", 1.0, client)),
                "coffee order");

        assertEquals("latte with oat milk", ranked.get(0).content());
    }

    // Degradation chain

    /** No query: pure importance-then-recency, identical to the default strategy. */
    @Test
    void rank_noQuery_pureImportanceThenRecency() {
        HybridRankingStrategy strategy = new HybridRankingStrategy(new VectorMapClient());

        List<MemoryEntry> ranked = strategy.rank(List.of(
                entry("a", "low", 0.1),
                entry("b", "high", 0.9)),
                null);

        assertEquals("high", ranked.get(0).content());
        assertEquals("low", ranked.get(1).content());
    }

    /** Legacy entry without a vector still surfaces through the lexical path. */
    @Test
    void rank_legacyEntryNoVector_lexicalPathStillWorks() {
        VectorMapClient client = new VectorMapClient()
                .put("latte", 1.0f, 0.0f)                              // query vector exists
                .put("tax: fiscal year deadline", 0.0f, 1.0f);        // orthogonal to query
        HybridRankingStrategy strategy = new HybridRankingStrategy(client);

        // "latte" matches the legacy entry's content lexically (token overlap 1.0)
        // and is orthogonal to tax. Semantic door is open but useless for both
        // (legacy has no vector; tax is orthogonal) — the lexical path decides.
        List<MemoryEntry> ranked = strategy.rank(List.of(
                entry("drink", "latte with oat milk", 0.2),      // no vector (legacy)
                vecEntry("tax", "fiscal year deadline", 0.9, client)),
                "latte");

        // latte: 0.3*1.0 + 0.2*0.2 = 0.34  vs  tax: 0.3*0 + 0.2*0.9 = 0.18
        assertEquals("latte with oat milk", ranked.get(0).content(),
                "legacy entry surfaces via β token overlap despite α=0");
    }

    /** Provider outage on the query embed: falls back to lexical + importance. */
    @Test
    void rank_queryEmbedFailure_degradesToLexicalPlusImportance() {
        VectorMapClient client = new VectorMapClient()
                .put("drink: latte with oat milk", 1.0f, 0.0f)
                .put("tax: fiscal year deadline", 0.0f, 1.0f);
        // No vector registered for query "latte" → tryEmbedQuery fails → degrade.
        HybridRankingStrategy strategy = new HybridRankingStrategy(client);

        List<MemoryEntry> ranked = strategy.rank(List.of(
                vecEntry("drink", "latte with oat milk", 0.2, client),
                vecEntry("tax", "fiscal year deadline", 0.9, client)),
                "latte");

        // Degraded: latte = 0.3*1.0 + 0.2*0.2 = 0.34  vs  tax = 0.3*0 + 0.2*0.9 = 0.18
        assertEquals("latte with oat milk", ranked.get(0).content(),
                "lexical path must still surface the coffee memory when the provider is down");
    }

    /** Dimension mismatch (provider swapped) is "no signal", not an exception. */
    @Test
    void rank_dimensionMismatch_treatedAsNoSignal() {
        VectorMapClient client = new VectorMapClient()
                .put("relocation plans", 1.0f, 0.0f)
                .put("home-city: lives in Shenzhen", 0.9f, 0.1f, 0.2f, 0.3f); // 4-dim vs 2-dim
        HybridRankingStrategy strategy = new HybridRankingStrategy(client);

        List<MemoryEntry> ranked = strategy.rank(List.of(
                        vecEntry("home-city", "lives in Shenzhen", 0.3, client),
                        entry("other", "unrelated note", 0.5)),
                "relocation plans");

        assertEquals(2, ranked.size(), "mismatched vector must not throw");
        assertEquals("unrelated note", ranked.get(0).content(),
                "with α=0 for the mismatched entry, γ importance decides");
    }

    // End-to-end through the retriever

    @Test
    void retriever_endToEnd_semanticRecallThroughRecallForContext() {
        VectorMapClient client = new VectorMapClient()
                .put("relocation plans", 1.0f, 0.0f)
                .put("home-city: lives in Shenzhen", 0.95f, 0.1f)
                .put("work: next week interview", 0.0f, 1.0f);
        var store = new io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore();
        store.write(vecEntry("home-city", "lives in Shenzhen", 0.3, client));
        store.write(vecEntry("work", "next week interview", 0.9, client));

        var retriever = new io.github.qwzhang01.agent.memory.MemoryRetriever(
                store, new HybridRankingStrategy(client));

        List<MemoryEntry> top1 = retriever.recallForContext(
                List.of("user:u1"), 1, "relocation plans");

        assertEquals("lives in Shenzhen", top1.get(0).content(),
                "end-to-end: semantic recall surfaces Shenzhen for a relocation question");
    }

    private static MemoryEntry entry(String subject, String content, double importance) {
        return new MemoryEntry(null, "user:u1", MemoryType.FACT, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", TS), MemoryStatus.ACTIVE, TS, null);
    }

    /** Entry whose vector is whatever the stub maps for its canonical embed text. */
    private static MemoryEntry vecEntry(String subject, String content, double importance,
                                        VectorMapClient client) {
        return entry(subject, content, importance).withEmbedding(
                client.embed(subject + ": " + content));
    }
}

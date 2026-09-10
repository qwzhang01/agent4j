package io.github.qwzhang01.agent.memory.ranking;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.memory.*;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the pluggable {@link RankingStrategy} abstraction and its built-in
 * implementations, plus integration with {@link MemoryRetriever}.
 *
 * <p>The existing {@code MemoryRetrieverTest} verifies the end-to-end behaviour
 * expected by callers.  This class focuses on:
 * <ol>
 *   <li>The default {@link ImportanceRankingStrategy} produces the same results
 *       as the pre-A8 {@code MemoryRetriever} (regression guard).</li>
 *   <li>A custom strategy injected via the new 2-arg constructor is actually used.</li>
 *   <li>{@link HybridRankingStrategy} (stub) delegates to importance ranking.</li>
 *   <li>{@code RankingStrategy.defaults()} factory works.</li>
 * </ol>
 */
class RankingStrategyTest {

    private static final Instant OLD = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant NEW = Instant.parse("2026-08-01T00:00:00Z");

    private InMemoryMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
    }

    // ============ ImportanceRankingStrategy ============

    @Test
    void importanceStrategy_noQuery_ranksByImportanceThenRecency() {
        write("s", "old-high", "high but old", 0.95, OLD);
        write("s", "new-low",  "low but new",  0.2,  NEW);
        write("s", "mid",      "mid",           0.5,  NEW);

        List<MemoryEntry> candidates = store.query(MemoryQuery.builder().scopes(List.of("s")).build());
        List<MemoryEntry> ranked = new ImportanceRankingStrategy().rank(candidates, null);

        assertEquals(3, ranked.size());
        assertEquals("high but old", ranked.get(0).content());
        assertEquals("mid",          ranked.get(1).content());
        assertEquals("low but new",  ranked.get(2).content());
    }

    @Test
    void importanceStrategy_withQuery_boostsMatchingEntry() {
        write("s", "coffee",    "user likes black coffee", 0.3, NEW);
        write("s", "interview", "next week job interview",  0.5, NEW);

        List<MemoryEntry> candidates = store.query(MemoryQuery.builder().scopes(List.of("s")).build());
        List<MemoryEntry> ranked = new ImportanceRankingStrategy().rank(candidates, "coffee today");

        assertEquals("user likes black coffee", ranked.get(0).content(),
                "query-matching entry should rank first despite lower base importance");
    }

    @Test
    void importanceStrategy_blankQuery_sameAsNoQuery() {
        write("s", "a", "higher", 0.9, NEW);
        write("s", "b", "lower",  0.1, NEW);

        List<MemoryEntry> candidates = store.query(MemoryQuery.builder().scopes(List.of("s")).build());
        ImportanceRankingStrategy strategy = new ImportanceRankingStrategy();

        List<String> withNull  = contents(strategy.rank(candidates, null));
        List<String> withBlank = contents(strategy.rank(candidates, "   "));

        assertEquals(withNull, withBlank, "blank query must produce same ordering as null");
    }

    @Test
    void importanceStrategy_emptyInput_returnsEmpty() {
        List<MemoryEntry> ranked = new ImportanceRankingStrategy().rank(List.of(), "any query");
        assertTrue(ranked.isEmpty());
    }

    // ============ HybridRankingStrategy (production since step 1) ============

    /**
     * The former stub-delegation test is obsolete: HybridRankingStrategy now
     * fuses semantic + lexical + importance. Its degradation contract (no
     * query → identical to ImportanceRankingStrategy) lives in
     * {@code HybridRankingStrategyTest}; here we keep one guard that the
     * no-arg-query path still equals the default strategy's ordering.
     */
    @Test
    void hybridStrategy_noQuery_matchesImportanceRanking() {
        write("s", "coffee",    "user likes black coffee", 0.3, NEW);
        write("s", "interview", "next week job interview",  0.5, NEW);

        List<MemoryEntry> candidates = store.query(MemoryQuery.builder().scopes(List.of("s")).build());
        List<MemoryEntry> byImportance = new ImportanceRankingStrategy().rank(candidates, null);

        HybridRankingStrategy hybrid = new HybridRankingStrategy(text -> new float[]{1.0f});
        List<MemoryEntry> byHybrid = hybrid.rank(candidates, null);

        assertEquals(contents(byImportance), contents(byHybrid),
                "no-query hybrid must produce identical results to ImportanceRankingStrategy");
    }

    // ============ RankingStrategy.defaults() factory ============

    @Test
    void defaults_factoryReturnsWorkingStrategy() {
        write("s", "a", "high", 0.9, NEW);
        write("s", "b", "low",  0.1, NEW);

        List<MemoryEntry> candidates = store.query(MemoryQuery.builder().scopes(List.of("s")).build());
        List<MemoryEntry> ranked = RankingStrategy.defaults().rank(candidates, null);

        assertEquals("high", ranked.get(0).content());
        assertEquals("low",  ranked.get(1).content());
    }

    // ============ MemoryRetriever custom strategy injection ============

    /**
     * A strategy that reverses importance order (lowest first) — used to prove
     * that the injected strategy is actually called inside {@code MemoryRetriever}.
     */
    @Test
    void retriever_usesInjectedStrategy() {
        write("s", "low",  "low importance",  0.1, NEW);
        write("s", "high", "high importance", 0.9, NEW);

        RankingStrategy reverseStrategy = (candidates, query) -> {
            List<MemoryEntry> copy = new ArrayList<>(candidates);
            // Deliberately reverse: lowest importance first
            copy.sort((a, b) -> Double.compare(a.importance(), b.importance()));
            return Collections.unmodifiableList(copy);
        };

        MemoryRetriever retriever = new MemoryRetriever(store, reverseStrategy);
        List<MemoryEntry> result = retriever.recallForContext(List.of("s"), 0, null);

        assertEquals("low importance",  result.get(0).content(),
                "custom strategy's ordering must be respected");
        assertEquals("high importance", result.get(1).content());
    }

    @Test
    void retriever_defaultConstructor_usesImportanceStrategy() {
        write("s", "old-high", "high but old", 0.95, OLD);
        write("s", "new-low",  "low but new",  0.2,  NEW);

        MemoryRetriever retriever = new MemoryRetriever(store);
        List<MemoryEntry> result = retriever.recallForContext(List.of("s"), 0);

        assertEquals("high but old", result.get(0).content(),
                "default constructor must behave identically to pre-A8 importance ranking");
    }

    @Test
    void retriever_limitAppliedAfterStrategyRank() {
        write("s", "a", "one",   0.9, NEW);
        write("s", "b", "two",   0.7, NEW);
        write("s", "c", "three", 0.5, NEW);

        MemoryRetriever retriever = new MemoryRetriever(store);
        List<MemoryEntry> result = retriever.recallForContext(List.of("s"), 2, null);

        assertEquals(2, result.size());
        assertEquals("one", result.get(0).content());
        assertEquals("two", result.get(1).content());
    }

    // ============ Helpers ============

    private void write(String scope, String subject, String content,
                       double importance, Instant createdAt) {
        store.write(new MemoryEntry(null, scope, MemoryType.FACT, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", createdAt),
                MemoryStatus.ACTIVE, createdAt, null));
    }

    private static List<String> contents(List<MemoryEntry> entries) {
        return entries.stream().map(MemoryEntry::content).toList();
    }
}

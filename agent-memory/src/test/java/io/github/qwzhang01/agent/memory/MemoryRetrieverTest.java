package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Context recall ranks by importance, then recency. No product-specific
 * priority (e.g. user-edited) lives here.
 */
class MemoryRetrieverTest {

    private InMemoryMemoryStore store;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        retriever = new MemoryRetriever(store);
    }

    // No-query (backward-compat)

    @Test
    void recallForContext_ranksByImportanceNotRecency() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-01T00:00:00Z");
        write("user:u1", "old-high", "high but old", 0.95, older);
        write("user:u1", "new-low", "low but new", 0.2, newer);
        write("user:u1", "mid", "mid", 0.5, newer);

        List<MemoryEntry> result = retriever.recallForContext(List.of("user:u1"), 2);

        assertEquals(2, result.size());
        assertEquals("high but old", result.get(0).content());
        assertEquals("mid", result.get(1).content());
    }

    @Test
    void recallForContext_sameImportance_newerFirst() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-01T00:00:00Z");
        write("user:u1", "a", "older", 0.7, older);
        write("user:u1", "b", "newer", 0.7, newer);

        List<MemoryEntry> result = retriever.recallForContext(List.of("user:u1"), 2);

        assertEquals("newer", result.get(0).content());
        assertEquals("older", result.get(1).content());
    }

    @Test
    void recallForContext_zeroLimit_returnsAllRanked() {
        write("user:u1", "low", "low", 0.1, Instant.parse("2026-08-01T00:00:00Z"));
        write("user:u1", "high", "high", 0.9, Instant.parse("2026-01-01T00:00:00Z"));

        List<MemoryEntry> result = retriever.recallForContext(List.of("user:u1"), 0);

        assertEquals(2, result.size());
        assertEquals("high", result.get(0).content());
        assertEquals("low", result.get(1).content());
    }


    /**
     * A lower-importance entry that matches the query should rank above a
     * higher-importance entry that does not match.
     * <p>
     * Math (QUERY_BOOST_WEIGHT = 0.5):
     * coffee: 0.3 + 0.5 * 0.5 (partial token overlap) = 0.55
     * interview: 0.5 + 0.5 * 0.0 = 0.50 → coffee wins
     */
    @Test
    void recallForContext_withQuery_boostsMatchingEntry() {
        Instant ts = Instant.parse("2026-08-01T00:00:00Z");
        write("user:u1", "coffee", "user likes black coffee", 0.3, ts);
        write("user:u1", "interview", "next week job interview", 0.5, ts);

        List<MemoryEntry> result = retriever.recallForContext(List.of("user:u1"), 0, "coffee today");

        assertEquals("user likes black coffee", result.get(0).content(),
                "query-matching entry should rank first despite lower base importance");
    }

    /** null query must produce the same ordering as the no-query 2-arg form. */
    @Test
    void recallForContext_nullQuery_degradesToBaseRanking() {
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-08-01T00:00:00Z");
        write("user:u1", "old-high", "high but old", 0.95, older);
        write("user:u1", "new-low", "low but new", 0.2, newer);

        List<MemoryEntry> withNull = retriever.recallForContext(List.of("user:u1"), 2, null);
        List<MemoryEntry> withoutQuery = retriever.recallForContext(List.of("user:u1"), 2);

        assertEquals(
                withoutQuery.stream().map(MemoryEntry::content).toList(),
                withNull.stream().map(MemoryEntry::content).toList(),
                "null query must produce same ordering as no-query form");
    }

    /** A query with no matching entries still respects limit and base ranking. */
    @Test
    void recallForContext_withQuery_respectsLimit() {
        Instant ts = Instant.parse("2026-08-01T00:00:00Z");
        write("user:u1", "a", "coffee drink", 0.9, ts);
        write("user:u1", "b", "coffee order", 0.8, ts);
        write("user:u1", "c", "unrelated fact", 0.7, ts);

        List<MemoryEntry> result = retriever.recallForContext(List.of("user:u1"), 2, "coffee");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.content().contains("coffee")),
                "top-2 should both match the query; unrelated entry should be displaced");
    }

    private void write(String scope, String subject, String content,
                       double importance, Instant createdAt) {
        store.write(new MemoryEntry(null, scope, MemoryType.FACT, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", createdAt),
                MemoryStatus.ACTIVE, createdAt, null));
    }
}

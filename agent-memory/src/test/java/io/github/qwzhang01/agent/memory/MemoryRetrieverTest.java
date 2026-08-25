package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private void write(String scope, String subject, String content,
                       double importance, Instant createdAt) {
        store.write(new MemoryEntry(null, scope, MemoryType.FACT, subject, content, importance,
                MemoryProvenance.userSaid("u1", "r1", createdAt),
                MemoryStatus.ACTIVE, createdAt, null));
    }
}

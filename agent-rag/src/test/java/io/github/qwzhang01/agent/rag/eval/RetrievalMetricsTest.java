package io.github.qwzhang01.agent.rag.eval;

import io.github.qwzhang01.agent.rag.model.Chunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrievalMetricsTest {

    static Chunk chunk(String docId, int ordinal, String... section) {
        return new Chunk(docId + "#" + ordinal, docId, null, null, List.of(section), "t", 0, 0, null, null);
    }

    private static final List<Chunk> RANKED = List.of(
            chunk("x.md", 1, "无关"),
            chunk("a.md", 2, "第八章", "记忆召回"),
            chunk("y.md", 3),
            chunk("b.md", 4, "重排"));

    private static final List<GoldRef> GOLD = List.of(
            new GoldRef("a.md", "召回"),
            new GoldRef("b.md", null),
            new GoldRef("c.md", ""));

    @Test
    void goldRefMatching() {
        assertTrue(new GoldRef("a.md", "召回").matches(RANKED.get(1)));
        assertTrue(new GoldRef("a.md", "第八章 > 记忆").matches(RANKED.get(1)));
        assertFalse(new GoldRef("a.md", "重排").matches(RANKED.get(1)));
        assertFalse(new GoldRef("A.md", null).matches(RANKED.get(1)));
        assertTrue(new GoldRef("b.md", " ").matches(RANKED.get(3)));
    }

    @Test
    void recallAtK() {
        assertEquals(0.0, RetrievalMetrics.recallAtK(RANKED, GOLD, 1));
        assertEquals(1.0 / 3, RetrievalMetrics.recallAtK(RANKED, GOLD, 3), 1e-9);
        assertEquals(2.0 / 3, RetrievalMetrics.recallAtK(RANKED, GOLD, 10), 1e-9);
        assertEquals(0.0, RetrievalMetrics.recallAtK(RANKED, List.of(), 5));
    }

    @Test
    void hitAtK() {
        assertEquals(0.0, RetrievalMetrics.hitAtK(RANKED, GOLD, 1));
        assertEquals(1.0, RetrievalMetrics.hitAtK(RANKED, GOLD, 2));
        assertEquals(0.0, RetrievalMetrics.hitAtK(List.of(), GOLD, 5));
    }

    @Test
    void reciprocalRank() {
        assertEquals(0.5, RetrievalMetrics.reciprocalRank(RANKED, GOLD));
        assertEquals(0.25, RetrievalMetrics.reciprocalRank(RANKED, List.of(new GoldRef("b.md", null))));
        assertEquals(0.0, RetrievalMetrics.reciprocalRank(RANKED, List.of(new GoldRef("c.md", null))));
    }

    @Test
    void foundAndInvalidK() {
        assertEquals(List.of(GOLD.get(0), GOLD.get(1)), RetrievalMetrics.found(RANKED, GOLD, 4));
        assertThrows(IllegalArgumentException.class, () -> RetrievalMetrics.recallAtK(RANKED, GOLD, 0));
    }
}

package io.github.qwzhang01.agent.rag.rerank;

import io.github.qwzhang01.agent.rag.RerankException;
import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FallbackRerankerTest {

    private static List<ScoredChunk> candidates() {
        return List.of(c(0), c(1), c(2));
    }

    private static ScoredChunk c(int i) {
        return ScoredChunk.of(new Chunk("d#" + i, "d", null, null, null, "t" + i, 0, 0, null, null),
                ScoredChunk.RRF, 1.0 - i * 0.1);
    }

    private static List<String> ids(List<ScoredChunk> list) {
        return list.stream().map(s -> s.chunk().chunkId()).toList();
    }

    private static final Reranker REVERSE = (q, cands, topN) -> cands.stream()
            .map(s -> s.withStage(ScoredChunk.RERANK, Integer.parseInt(s.chunk().chunkId().substring(2)) * 0.4))
            .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
            .limit(topN)
            .toList();

    @Test
    void degradesOnRerankException() {
        FallbackReranker reranker = new FallbackReranker((q, c, n) -> {
            throw new RerankException("HTTP 503");
        });
        FallbackReranker.Outcome out = reranker.rerankWithOutcome("q", candidates(), 2);
        assertTrue(out.degraded());
        assertTrue(out.error().contains("HTTP 503"));
        assertEquals(List.of("d#0", "d#1"), ids(out.results()));
        assertEquals(List.of("d#0", "d#1"), ids(reranker.rerank("q", candidates(), 2)));
    }

    @Test
    void degradesOnAnyRuntimeException() {
        FallbackReranker reranker = new FallbackReranker((q, c, n) -> {
            throw new IllegalStateException("boom");
        }, 0.9);
        FallbackReranker.Outcome out = reranker.rerankWithOutcome("q", candidates(), 5);
        assertTrue(out.degraded());
        assertEquals(3, out.results().size());
    }

    @Test
    void passesThroughOnSuccess() {
        FallbackReranker.Outcome out = new FallbackReranker(REVERSE).rerankWithOutcome("q", candidates(), 2);
        assertFalse(out.degraded());
        assertNull(out.error());
        assertEquals(List.of("d#2", "d#1"), ids(out.results()));
    }

    @Test
    void minScoreDropsLowResultsAndMayEmpty() {
        assertEquals(List.of("d#2"), ids(new FallbackReranker(REVERSE, 0.5).rerank("q", candidates(), 3)));
        FallbackReranker.Outcome none = new FallbackReranker(REVERSE, 0.95).rerankWithOutcome("q", candidates(), 3);
        assertFalse(none.degraded());
        assertTrue(none.results().isEmpty());
    }

    @Test
    void degradationIsVisibleThroughTheRerankerInterface() {
        Reranker asInterface = new FallbackReranker((q, c, n) -> {
            throw new RerankException("HTTP 503");
        });
        Reranker.Outcome out = asInterface.rerankWithOutcome("q", candidates(), 2);
        assertTrue(out.degraded());
        assertEquals(List.of("d#0", "d#1"), ids(out.results()));
    }

    @Test
    void defaultOutcomeWrapsRerankAndRejectsNull() {
        Reranker.Outcome ok = REVERSE.rerankWithOutcome("q", candidates(), 1);
        assertFalse(ok.degraded());
        assertEquals(List.of("d#2"), ids(ok.results()));
        Reranker returnsNull = (q, c, n) -> null;
        assertThrows(RerankException.class, () -> returnsNull.rerankWithOutcome("q", candidates(), 1));
    }

    @Test
    void emptyCandidatesSkipDelegate() {
        FallbackReranker reranker = new FallbackReranker((q, c, n) -> fail("must not be called"));
        assertEquals(List.of(), reranker.rerank("q", List.of(), 3));
    }

    @Test
    void noOpKeepsHead() {
        assertEquals(List.of("d#0", "d#1"), ids(NoOpReranker.INSTANCE.rerank("q", candidates(), 2)));
        assertEquals(3, NoOpReranker.INSTANCE.rerank("q", candidates(), 10).size());
        assertEquals(List.of(), NoOpReranker.INSTANCE.rerank("q", candidates(), 0));
    }
}

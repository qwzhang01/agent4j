package io.github.qwzhang01.agent.rag.retrieve;

import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static io.github.qwzhang01.agent.rag.index.TestChunks.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReciprocalRankFusionTest {

    private static ScoredChunk hit(String id, String stage, double score) {
        return ScoredChunk.of(chunk(id, 0, id), stage, score);
    }

    private static List<ScoredChunk> list(String stage, String... ids) {
        return Arrays.stream(ids).map(id -> hit(id, stage, 1.0)).toList();
    }

    private static List<String> ids(List<ScoredChunk> hits) {
        return hits.stream().map(h -> h.chunk().docId()).toList();
    }

    @Test
    void fusesHandComputedExample() {
        List<ScoredChunk> bm25 = List.of(hit("a", ScoredChunk.BM25, 9.0), hit("b", ScoredChunk.BM25, 7.0),
                hit("c", ScoredChunk.BM25, 5.0));
        List<ScoredChunk> vector = List.of(hit("b", ScoredChunk.VECTOR, 0.9), hit("d", ScoredChunk.VECTOR, 0.8),
                hit("a", ScoredChunk.VECTOR, 0.7));

        List<ScoredChunk> fused = new ReciprocalRankFusion().fuse(List.of(bm25, vector), 10);

        // b: 1/62 + 1/61, a: 1/61 + 1/63, d: 1/62, c: 1/63
        assertEquals(List.of("b", "a", "d", "c"), ids(fused));
        assertEquals(1.0 / 62 + 1.0 / 61, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 61 + 1.0 / 63, fused.get(1).score(), 1e-12);
        assertEquals(1.0 / 62, fused.get(2).score(), 1e-12);
        assertEquals(1.0 / 63, fused.get(3).score(), 1e-12);

        ScoredChunk a = fused.get(1);
        assertEquals(9.0, a.signals().get(ScoredChunk.BM25));
        assertEquals(0.7, a.signals().get(ScoredChunk.VECTOR));
        assertEquals(a.score(), a.signals().get(ScoredChunk.RRF));
        assertEquals(2, fused.get(3).signals().size());
    }

    @Test
    void truncatesToTopK() {
        List<ScoredChunk> fused = new ReciprocalRankFusion().fuse(List.of(list("bm25", "a", "b", "c")), 2);

        assertEquals(List.of("a", "b"), ids(fused));
        assertEquals(60, new ReciprocalRankFusion().k());
    }

    @Test
    void equalScoresBreakTiesByChunkId() {
        List<ScoredChunk> fused = new ReciprocalRankFusion().fuse(List.of(list("bm25", "b"), list("vector", "a")), 5);

        assertEquals(List.of("a", "b"), ids(fused));
    }

    @Test
    void equalScoresPreferBestIndividualRank() {
        // k = 1: p scores 1/4 + 1/4 (rank 3 twice), q scores 1/2 (rank 1 once)
        List<ScoredChunk> fused = new ReciprocalRankFusion(1).fuse(List.of(
                list("x", "x1", "x2", "p"),
                list("y", "y1", "y2", "p"),
                list("z", "q")), 10);

        List<String> order = ids(fused);
        assertEquals(0.5, fused.get(order.indexOf("p")).score(), 0.0);
        assertEquals(0.5, fused.get(order.indexOf("q")).score(), 0.0);
        assertTrue(order.indexOf("q") < order.indexOf("p"));
    }

    @Test
    void weightsScaleListContributions() {
        ReciprocalRankFusion weighted = new ReciprocalRankFusion(60, 1.0, 3.0);

        List<ScoredChunk> fused = weighted.fuse(List.of(list("bm25", "a", "b"), list("vector", "b", "a")), 2);

        assertEquals(List.of("b", "a"), ids(fused));
        assertEquals(1.0 / 62 + 3.0 / 61, fused.get(0).score(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> weighted.fuse(List.of(list("bm25", "a")), 2));
    }

    @Test
    void duplicateWithinListCountsOnceAtBestRank() {
        List<ScoredChunk> fused = new ReciprocalRankFusion().fuse(List.of(list("bm25", "a", "a", "b")), 5);

        assertEquals(List.of("a", "b"), ids(fused));
        assertEquals(1.0 / 61, fused.get(0).score(), 1e-12);
        assertEquals(1.0 / 62, fused.get(1).score(), 1e-12);
    }

    @Test
    void degenerateInputsYieldEmptyList() {
        ReciprocalRankFusion rrf = new ReciprocalRankFusion();

        assertTrue(rrf.fuse(null, 5).isEmpty());
        assertTrue(rrf.fuse(List.of(), 5).isEmpty());
        assertTrue(rrf.fuse(List.of(list("bm25", "a")), 0).isEmpty());
        assertEquals(List.of("a"), ids(rrf.fuse(Arrays.asList(null, list("bm25", "a")), 5)));
    }

    @Test
    void invalidConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ReciprocalRankFusion(0));
        assertThrows(IllegalArgumentException.class, () -> new ReciprocalRankFusion(60, 1.0, -1.0));
        assertEquals(List.of("a"), ids(new ReciprocalRankFusion(60, new double[0]).fuse(List.of(list("bm25", "a")), 1)));
    }
}

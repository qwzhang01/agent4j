package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qwzhang01.agent.rag.index.TestChunks.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LuceneChunkIndexTest {

    private LuceneChunkIndex index;

    @AfterEach
    void tearDown() {
        if (index != null) {
            index.close();
        }
    }

    private static List<float[]> nulls(int n) {
        return Arrays.asList(new float[n][]);
    }

    private static List<String> ids(List<ScoredChunk> hits) {
        return hits.stream().map(h -> h.chunk().chunkId()).toList();
    }

    @Test
    void getRoundTripsEveryChunkField() {
        index = LuceneChunkIndex.inMemory(3);
        Chunk original = new Chunk("guide.md#0", "guide.md", "Guide", "/abs/guide.md",
                List.of("Install", "Linux"), "Run the installer.", 3, 9, 2,
                Map.of("version", "2.1", "product", "agent4j"));
        Chunk noPage = new Chunk("guide.md#1", "guide.md", "Guide", null, List.of(), "", 0, 0, null, Map.of());

        index.upsert("guide.md", "h1", List.of(original, noPage), List.of(new float[]{1, 0, 0}, new float[]{0, 1, 0}));

        assertEquals(Optional.of(original), index.get("guide.md#0"));
        assertEquals(Optional.of(noPage), index.get("guide.md#1"));
        assertEquals(Optional.empty(), index.get("missing#0"));
        assertEquals(2, index.size());
        assertEquals(3, index.dimensions());
    }

    @Test
    void persistsAcrossReopen(@TempDir Path dir) {
        index = LuceneChunkIndex.open(dir, 3);
        index.upsert("a.md", "hash-a", List.of(chunk("a.md", 0, "persistent lucene index")),
                List.<float[]>of(new float[]{1, 0, 0}));
        index.close();

        index = LuceneChunkIndex.open(dir, 3);
        assertEquals(Optional.of("hash-a"), index.contentHash("a.md"));
        assertEquals(Set.of("a.md"), index.docIds());
        assertEquals(1, index.size());
        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("persistent", 5, Map.of())));
        assertEquals(List.of("a.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 5, Map.of())));
    }

    @Test
    void reopenWithOtherDimensionsIsRejected(@TempDir Path dir) {
        LuceneChunkIndex.open(dir, 3).close();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> LuceneChunkIndex.open(dir, 4));
        assertTrue(e.getMessage().contains("3"));
        index = LuceneChunkIndex.open(dir, 3);
    }

    @Test
    void invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> LuceneChunkIndex.inMemory(0));
        assertThrows(IllegalArgumentException.class, () -> LuceneChunkIndex.inMemory(LuceneChunkIndex.MAX_DIMENSIONS + 1));
    }

    @Test
    void supportsVectorsAboveLuceneDefaultLimit() {
        index = LuceneChunkIndex.inMemory(1536);
        float[] v = new float[1536];
        v[1000] = 1f;
        index.upsert("big.md", "h", List.of(chunk("big.md", 0, "large embedding")), List.<float[]>of(v));

        assertEquals(List.of("big.md#0"), ids(index.vectorSearch(v, 3, Map.of())));
    }

    @Test
    void upsertReplacesAllChunksOfDocument() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "v1", List.of(
                chunk("a.md", 0, "alpha legacy"), chunk("a.md", 1, "beta legacy"), chunk("a.md", 2, "gamma legacy")), nulls(3));
        index.upsert("b.md", "b1", List.of(chunk("b.md", 0, "unrelated")), nulls(1));

        index.upsert("a.md", "v2", List.of(chunk("a.md", 0, "alpha fresh")), nulls(1));

        assertEquals(2, index.size());
        assertEquals(Optional.of("v2"), index.contentHash("a.md"));
        assertTrue(index.get("a.md#1").isEmpty());
        assertTrue(index.keywordSearch("legacy", 10, Map.of()).isEmpty());
        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("fresh", 10, Map.of())));
        assertEquals(Set.of("a.md", "b.md"), index.docIds());
    }

    @Test
    void deleteRemovesChunksAndMarker() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "h", List.of(chunk("a.md", 0, "to be removed")), nulls(1));
        index.upsert("b.md", "h", List.of(chunk("b.md", 0, "kept")), nulls(1));

        index.delete("a.md");
        index.delete("never-indexed.md");

        assertEquals(Set.of("b.md"), index.docIds());
        assertTrue(index.contentHash("a.md").isEmpty());
        assertTrue(index.get("a.md#0").isEmpty());
        assertEquals(1, index.size());
    }

    @Test
    void documentWithoutChunksIsStillTracked() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("empty.md", "h0", List.of(), List.of());

        assertEquals(Set.of("empty.md"), index.docIds());
        assertEquals(Optional.of("h0"), index.contentHash("empty.md"));
        assertEquals(0, index.size());
        assertTrue(index.keywordSearch("empty", 5, Map.of()).isEmpty());
    }

    @Test
    void emptyIndexAnswersQueries() {
        index = LuceneChunkIndex.inMemory(3);

        assertEquals(Set.of(), index.docIds());
        assertEquals(0, index.size());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 5, Map.of()).isEmpty());
        assertTrue(index.keywordSearch("anything", 5, Map.of()).isEmpty());
    }

    @Test
    void chineseKeywordSearchSegmentsWords() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("rag.md", "h", List.of(
                chunk("rag.md", 0, "引用溯源的实现依赖稳定的 chunk 编号"),
                chunk("rag.md", 1, "向量检索使用余弦相似度")), nulls(2));

        List<ScoredChunk> hits = index.keywordSearch("引用溯源", 5, Map.of());

        assertEquals("rag.md#0", hits.get(0).chunk().chunkId());
        assertTrue(hits.get(0).signals().containsKey(ScoredChunk.BM25));
        assertEquals(hits.get(0).score(), hits.get(0).signals().get(ScoredChunk.BM25));
        assertEquals(1, hits.size());
    }

    @Test
    void keywordSearchUsesOrSemanticsAndRanksByBm25() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "h", List.of(
                chunk("a.md", 0, "retry policy with exponential backoff"),
                chunk("a.md", 1, "retry once"),
                chunk("a.md", 2, "database connection pool")), nulls(3));

        List<ScoredChunk> hits = index.keywordSearch("exponential retry", 10, Map.of());

        assertEquals(List.of("a.md#0", "a.md#1"), ids(hits));
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertEquals(1, index.keywordSearch("exponential retry", 1, Map.of()).size());
    }

    @Test
    void keywordSearchMatchesTitleAndSectionViaContextualText() {
        index = LuceneChunkIndex.inMemory(3);
        Chunk c = new Chunk("cfg.md#0", "cfg.md", "Timeouts", null, List.of("Gateway"), "Set it to 30.", 1, 1, null, Map.of());
        index.upsert("cfg.md", "h", List.of(c), nulls(1));

        assertEquals(List.of("cfg.md#0"), ids(index.keywordSearch("gateway timeouts", 5, Map.of())));
    }

    @Test
    void keywordSearchToleratesQuerySyntaxAndBlankInput() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "h", List.of(chunk("a.md", 0, "foo bar baz")), nulls(1));

        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("foo:bar AND (baz\" OR -[", 5, Map.of())));
        assertTrue(index.keywordSearch("   ", 5, Map.of()).isEmpty());
        assertTrue(index.keywordSearch(null, 5, Map.of()).isEmpty());
        assertTrue(index.keywordSearch("foo", 0, Map.of()).isEmpty());
        assertTrue(index.keywordSearch("，。！", 5, Map.of()).isEmpty());
        assertEquals(1, index.keywordSearch("foo " + "x ".repeat(2000), 5, null).size());
    }

    @Test
    void filtersRestrictKeywordAndVectorSearch() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("v1.md", "h", List.of(chunk("v1.md", 0, "install guide", Map.of("version", "1"))),
                List.<float[]>of(new float[]{1, 0, 0}));
        index.upsert("v2.md", "h", List.of(chunk("v2.md", 0, "install guide", Map.of("version", "2", "lang", "en"))),
                List.<float[]>of(new float[]{1, 0, 0}));

        assertEquals(List.of("v2.md#0"), ids(index.keywordSearch("install", 5, Map.of("version", "2"))));
        assertEquals(List.of("v1.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 5, Map.of("version", "1"))));
        assertEquals(List.of("v2.md#0"), ids(index.keywordSearch("install", 5, Map.of("version", "2", "lang", "en"))));
        assertTrue(index.keywordSearch("install", 5, Map.of("version", "3")).isEmpty());
        assertEquals(2, index.vectorSearch(new float[]{1, 0, 0}, 5, null).size());
    }

    @Test
    void vectorSearchOrdersByCosineSimilarity() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "h", List.of(
                chunk("a.md", 0, "orthogonal"), chunk("a.md", 1, "identical"), chunk("a.md", 2, "diagonal")),
                List.of(new float[]{0, 1, 0}, new float[]{2, 0, 0}, new float[]{1, 1, 0}));

        List<ScoredChunk> hits = index.vectorSearch(new float[]{1, 0, 0}, 3, Map.of());

        assertEquals(List.of("a.md#1", "a.md#2", "a.md#0"), ids(hits));
        assertEquals(1.0, hits.get(0).score(), 1e-5);
        assertEquals((1 + Math.sqrt(0.5)) / 2, hits.get(1).score(), 1e-5);
        assertEquals(0.5, hits.get(2).score(), 1e-5);
        assertEquals(hits.get(0).score(), hits.get(0).signals().get(ScoredChunk.VECTOR));
        assertEquals(List.of("a.md#1"), ids(index.vectorSearch(new float[]{1, 0, 0}, 1, Map.of())));
    }

    @Test
    void vectorSearchWithFakeEmbeddingsPrefersSharedTokens() {
        FakeEmbeddingClient embedder = new FakeEmbeddingClient(64);
        index = LuceneChunkIndex.inMemory(64);
        List<Chunk> chunks = List.of(
                chunk("a.md", 0, "citation tracing for answers"),
                chunk("a.md", 1, "gardening tomatoes in summer"));
        index.upsert("a.md", "h", chunks, embedder.embedAll(chunks.stream().map(Chunk::contextualText).toList()));

        List<ScoredChunk> hits = index.vectorSearch(embedder.embed("citation tracing"), 2, Map.of());

        assertEquals("a.md#0", hits.get(0).chunk().chunkId());
    }

    @Test
    void chunksWithoutUsableVectorAreKeywordOnly() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "h", List.of(
                chunk("a.md", 0, "has vector"), chunk("a.md", 1, "null vector"), chunk("a.md", 2, "zero vector")),
                Arrays.asList(new float[]{1, 0, 0}, null, new float[]{0, 0, 0}));

        assertEquals(List.of("a.md#0"), ids(index.vectorSearch(new float[]{1, 1, 1}, 5, Map.of())));
        assertEquals(List.of("a.md#2"), ids(index.keywordSearch("zero", 5, Map.of())));
        assertTrue(index.vectorSearch(new float[]{0, 0, 0}, 5, Map.of()).isEmpty());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 0, Map.of()).isEmpty());

        index.upsert("b.md", "h", List.of(chunk("b.md", 0, "no vectors list")), null);
        assertTrue(index.get("b.md#0").isPresent());
    }

    @Test
    void invalidUpsertsAreRejectedWithoutPartialWrites() {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "v1", List.of(chunk("a.md", 0, "original")), nulls(1));

        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x")), List.<float[]>of(new float[]{1, 0})));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x"), chunk("a.md", 1, "y")), nulls(1)));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("other.md", 0, "x")), nulls(1)));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x"), chunk("a.md", 0, "y")), nulls(2)));
        assertThrows(IllegalArgumentException.class, () -> index.vectorSearch(new float[]{1, 0}, 3, Map.of()));

        assertEquals(Optional.of("v1"), index.contentHash("a.md"));
        assertEquals("original", index.get("a.md#0").orElseThrow().text());
    }

    @Test
    void closeIsIdempotentAndBlocksFurtherUse() {
        index = LuceneChunkIndex.inMemory(3);
        index.close();
        index.close();

        assertThrows(IllegalStateException.class, () -> index.size());
        assertThrows(IllegalStateException.class, () -> index.upsert("a.md", "h", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> index.delete("a.md"));
    }

    @Test
    void concurrentReadersNeverSeeHalfReplacedDocument() throws Exception {
        index = LuceneChunkIndex.inMemory(3);
        index.upsert("a.md", "v0", versionChunks(0), nulls(3));
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> violation = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            while (!done.get() && violation.get() == null) {
                long size = index.size();
                List<ScoredChunk> hits = index.keywordSearch("marker", 10, Map.of());
                Set<String> versions = new java.util.HashSet<>();
                hits.forEach(h -> versions.add(h.chunk().text().split(" ")[1]));
                if (size != 3 || hits.size() != 3 || versions.size() != 1) {
                    violation.set("size=" + size + " hits=" + hits.size() + " versions=" + versions);
                }
            }
        });
        reader.start();
        for (int v = 1; v <= 60; v++) {
            index.upsert("a.md", "v" + v, versionChunks(v), nulls(3));
        }
        done.set(true);
        reader.join();

        assertNull(violation.get());
        assertEquals(Optional.of("v60"), index.contentHash("a.md"));
    }

    private static List<Chunk> versionChunks(int version) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunks.add(chunk("a.md", i, "marker v" + version + " part" + i));
        }
        return chunks;
    }

    @Test
    void nullArgumentsAreRejected() {
        index = LuceneChunkIndex.inMemory(3);

        assertThrows(NullPointerException.class, () -> index.upsert(null, "h", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> index.get(null));
        assertThrows(NullPointerException.class, () -> index.vectorSearch(null, 3, Map.of()));
        assertThrows(NullPointerException.class, () -> LuceneChunkIndex.open(null, 3));
        assertFalse(index.contentHash("x").isPresent());
    }
}

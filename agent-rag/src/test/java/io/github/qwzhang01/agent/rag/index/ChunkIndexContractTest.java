package io.github.qwzhang01.agent.rag.index;

import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.qwzhang01.agent.rag.index.TestChunks.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour every {@link ChunkIndex} backend must share. Subclasses provide {@link #open(int)};
 * each call within one test opens the same storage, so data written before a close is visible
 * after the next open. Keyword scores differ between backends; only rankings are asserted.
 */
public abstract class ChunkIndexContractTest {

    private final List<ChunkIndex> opened = new ArrayList<>();

    /** Opens (or reopens) this test's storage with {@code dimensions}. */
    protected abstract ChunkIndex open(int dimensions);

    @AfterEach
    void closeOpened() {
        opened.forEach(ChunkIndex::close);
    }

    protected ChunkIndex index(int dimensions) {
        ChunkIndex index = open(dimensions);
        opened.add(index);
        return index;
    }

    protected static List<float[]> nulls(int n) {
        return Arrays.asList(new float[n][]);
    }

    protected static List<String> ids(List<ScoredChunk> hits) {
        return hits.stream().map(h -> h.chunk().chunkId()).toList();
    }

    private static SearchFilter prefixes(String... prefixes) {
        return SearchFilter.none().withDocIdPrefixes(List.of(prefixes));
    }

    @Test
    public void getRoundTripsEveryChunkField() {
        ChunkIndex index = index(3);
        Chunk full = new Chunk("docs/指南 a.md#0", "docs/指南 a.md", "指南", "/abs/docs/指南 a.md",
                List.of("安装", "Linux 'quoted' \\ path"), "运行安装程序 🚀 it's", 3, 9, 2,
                Map.of("version", "2.1", "产品", "agent4j", "quote", "a'b\"c"));
        Chunk bare = new Chunk("docs/指南 a.md#1", "docs/指南 a.md", "指南", null, List.of(), "", 0, 0, null, Map.of());

        index.upsert("docs/指南 a.md", "h1", List.of(full, bare), List.of(new float[]{1, 0, 0}, new float[]{0, 1, 0}));

        assertEquals(Optional.of(full), index.get("docs/指南 a.md#0"));
        assertEquals(Optional.of(bare), index.get("docs/指南 a.md#1"));
        assertEquals(Optional.empty(), index.get("missing#0"));
        assertEquals(2, index.size());
        assertEquals(Set.of("docs/指南 a.md"), index.docIds());
        assertEquals(Optional.of(full), index.keywordSearch("安装程序", 1, SearchFilter.none()).stream()
                .findFirst().map(ScoredChunk::chunk));
    }

    @Test
    public void upsertReplacesAllChunksOfDocument() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "v1", List.of(
                chunk("a.md", 0, "alpha legacy"), chunk("a.md", 1, "beta legacy"), chunk("a.md", 2, "gamma legacy")), nulls(3));
        index.upsert("b.md", "b1", List.of(chunk("b.md", 0, "unrelated")), nulls(1));

        index.upsert("a.md", "v2", List.of(chunk("a.md", 0, "alpha fresh")), nulls(1));

        assertEquals(2, index.size());
        assertEquals(Optional.of("v2"), index.contentHash("a.md"));
        assertTrue(index.get("a.md#1").isEmpty());
        assertTrue(index.keywordSearch("legacy", 10, SearchFilter.none()).isEmpty());
        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("fresh", 10, SearchFilter.none())));
        assertEquals(Set.of("a.md", "b.md"), index.docIds());
    }

    @Test
    public void deleteRemovesChunksAndDocument() {
        ChunkIndex index = index(3);
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
    public void documentWithoutChunksIsStillTracked() {
        ChunkIndex index = index(3);
        index.upsert("empty.md", "h0", List.of(), List.of());

        assertEquals(Set.of("empty.md"), index.docIds());
        assertEquals(Optional.of("h0"), index.contentHash("empty.md"));
        assertEquals(0, index.size());
        assertTrue(index.keywordSearch("empty", 5, SearchFilter.none()).isEmpty());
    }

    @Test
    public void emptyIndexAnswersQueries() {
        ChunkIndex index = index(3);

        assertEquals(Set.of(), index.docIds());
        assertEquals(0, index.size());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 5, SearchFilter.none()).isEmpty());
        assertTrue(index.keywordSearch("anything", 5, SearchFilter.none()).isEmpty());
    }

    @Test
    public void chineseKeywordSearchSegmentsWords() {
        ChunkIndex index = index(3);
        index.upsert("rag.md", "h", List.of(
                chunk("rag.md", 0, "引用溯源的实现依赖稳定的 chunk 编号"),
                chunk("rag.md", 1, "向量检索使用余弦相似度")), nulls(2));

        List<ScoredChunk> hits = index.keywordSearch("引用溯源", 5, SearchFilter.none());

        assertEquals(List.of("rag.md#0"), ids(hits));
        assertEquals(hits.get(0).score(), hits.get(0).signals().get(ScoredChunk.BM25));
        assertEquals(List.of("rag.md#1"), ids(index.keywordSearch("余弦相似度", 5, SearchFilter.none())));
    }

    @Test
    public void keywordSearchUsesOrSemanticsAndRanksBestFirst() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "h", List.of(
                chunk("a.md", 0, "retry policy with exponential backoff"),
                chunk("a.md", 1, "retry once"),
                chunk("a.md", 2, "database connection pool")), nulls(3));

        List<ScoredChunk> hits = index.keywordSearch("exponential retry", 10, SearchFilter.none());

        assertEquals(List.of("a.md#0", "a.md#1"), ids(hits));
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertEquals(1, index.keywordSearch("exponential retry", 1, SearchFilter.none()).size());
    }

    @Test
    public void keywordSearchMatchesTitleAndSectionViaContextualText() {
        ChunkIndex index = index(3);
        Chunk c = new Chunk("cfg.md#0", "cfg.md", "Timeouts", null, List.of("Gateway"), "Set it to 30.", 1, 1, null, Map.of());
        index.upsert("cfg.md", "h", List.of(c), nulls(1));

        assertEquals(List.of("cfg.md#0"), ids(index.keywordSearch("gateway timeouts", 5, SearchFilter.none())));
    }

    @Test
    public void keywordSearchToleratesQuerySyntaxAndBlankInput() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "h", List.of(chunk("a.md", 0, "foo bar baz it's a\\b 'x' | y & !z :*")), nulls(1));

        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("foo:bar AND (baz\" OR -[", 5, SearchFilter.none())));
        assertEquals(List.of("a.md#0"), ids(index.keywordSearch("it's a\\b 'x' | y & !z :* <-> ''", 5, SearchFilter.none())));
        assertTrue(index.keywordSearch("   ", 5, SearchFilter.none()).isEmpty());
        assertTrue(index.keywordSearch(null, 5, SearchFilter.none()).isEmpty());
        assertTrue(index.keywordSearch("foo", 0, SearchFilter.none()).isEmpty());
        assertTrue(index.keywordSearch("，。！", 5, SearchFilter.none()).isEmpty());
        assertEquals(1, index.keywordSearch("foo " + "x ".repeat(2000), 5, (SearchFilter) null).size());
    }

    @Test
    public void metadataFiltersRestrictKeywordAndVectorSearch() {
        ChunkIndex index = index(3);
        index.upsert("v1.md", "h", List.of(chunk("v1.md", 0, "install guide", Map.of("version", "1"))),
                List.<float[]>of(new float[]{1, 0, 0}));
        index.upsert("v2.md", "h", List.of(chunk("v2.md", 0, "install guide", Map.of("version", "2", "lang", "en"))),
                List.<float[]>of(new float[]{1, 0, 0}));

        assertEquals(List.of("v2.md#0"), ids(index.keywordSearch("install", 5, Map.of("version", "2"))));
        assertEquals(List.of("v1.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 5, Map.of("version", "1"))));
        assertEquals(List.of("v2.md#0"), ids(index.keywordSearch("install", 5,
                SearchFilter.of(Map.of("version", "2", "lang", "en")))));
        assertTrue(index.keywordSearch("install", 5, Map.of("version", "3")).isEmpty());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 5, SearchFilter.of(Map.of("lang", "fr"))).isEmpty());
        assertEquals(2, index.vectorSearch(new float[]{1, 0, 0}, 5, (Map<String, String>) null).size());
    }

    @Test
    public void docIdPrefixesRestrictKeywordAndVectorSearch() {
        ChunkIndex index = populateTeams(index(3));

        assertEquals(Set.of("team-a/guide.md#0", "team-a/sub/deep.md#0"),
                Set.copyOf(ids(index.keywordSearch("guide", 10, prefixes("team-a/")))));
        assertEquals(Set.of("team-a/guide.md#0", "team-a/sub/deep.md#0"),
                Set.copyOf(ids(index.vectorSearch(new float[]{1, 0, 0}, 10, prefixes("team-a/")))));
        assertEquals(List.of("team-b/guide.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 10,
                prefixes("team-b/", "team-c/"))));
        assertEquals(List.of("team-a/sub/deep.md#0"), ids(index.keywordSearch("guide", 10,
                prefixes("team-a/sub/"))));
        assertEquals(List.of("team-b/guide.md#0"), ids(index.keywordSearch("guide", 10,
                prefixes("team-b/guide.md"))));
        assertEquals(4, index.keywordSearch("guide", 10, prefixes("")).size());
        assertEquals(4, index.vectorSearch(new float[]{1, 0, 0}, 10, SearchFilter.none().withDocIdPrefixes(null)).size());
    }

    @Test
    public void emptyAllowedSetMatchesNothing() {
        ChunkIndex index = populateTeams(index(3));
        SearchFilter nothing = SearchFilter.none().withDocIdPrefixes(List.of());

        assertTrue(index.keywordSearch("guide", 10, nothing).isEmpty());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 10, nothing).isEmpty());
        assertTrue(index.keywordSearch("guide", 10, nothing.withMetadata(Map.of("team", "a"))).isEmpty());
    }

    @Test
    public void docIdPrefixesAndMetadataFiltersCombine() {
        ChunkIndex index = populateTeams(index(3));
        SearchFilter aPublic = SearchFilter.of(Map.of("level", "public")).withDocIdPrefixes(List.of("team-a/"));

        assertEquals(List.of("team-a/guide.md#0"), ids(index.keywordSearch("guide", 10, aPublic)));
        assertEquals(List.of("team-a/guide.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 10, aPublic)));
        assertTrue(index.keywordSearch("guide", 10, aPublic.withMetadata(Map.of("level", "secret"))
                .withDocIdPrefixes(List.of("team-b/"))).isEmpty());
    }

    @Test
    public void prefixCharactersAreLiteral() {
        ChunkIndex index = index(3);
        for (String docId : List.of("a_b/x.md", "aXb/x.md", "100%/y.md", "100 pct/y.md", "c\\d/z.md", "cd/z.md")) {
            index.upsert(docId, "h", List.of(chunk(docId, 0, "shared literal text")), List.<float[]>of(new float[]{1, 0, 0}));
        }

        assertEquals(List.of("a_b/x.md#0"), ids(index.keywordSearch("literal", 10, prefixes("a_b/"))));
        assertEquals(List.of("100%/y.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 10, prefixes("100%"))));
        assertEquals(List.of("c\\d/z.md#0"), ids(index.keywordSearch("literal", 10, prefixes("c\\"))));
        assertTrue(index.keywordSearch("literal", 10, prefixes("*", "a%", "_")).isEmpty());
    }

    @Test
    public void manyPrefixesDoNotThrow() {
        ChunkIndex index = populateTeams(index(3));
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 499; i++) {
            many.add("user-" + i + "/");
        }
        many.add("team-b/");
        SearchFilter filter = SearchFilter.of(Map.of("level", "public")).withDocIdPrefixes(many);

        assertEquals(List.of("team-b/guide.md#0"), ids(index.keywordSearch("guide " + "词".repeat(300), 10, filter)));
        assertEquals(List.of("team-b/guide.md#0"), ids(index.vectorSearch(new float[]{1, 0, 0}, 10, filter)));
    }

    @Test
    public void restrictiveFilterStillFillsTopK() {
        ChunkIndex index = index(8);
        java.util.Random random = new java.util.Random(7);
        for (int d = 0; d < 40; d++) {
            List<Chunk> chunks = new ArrayList<>();
            List<float[]> vectors = new ArrayList<>();
            String docId = (d < 2 ? "rare/" : "common/") + d + ".md";
            for (int i = 0; i < 10; i++) {
                chunks.add(chunk(docId, i, "common words chunk " + i));
                float[] v = new float[8];
                for (int k = 0; k < 8; k++) {
                    v[k] = (float) random.nextGaussian();
                }
                if (docId.startsWith("rare/")) {
                    v[0] = -10;
                }
                vectors.add(v);
            }
            index.upsert(docId, "h", chunks, vectors);
        }
        float[] query = new float[8];
        query[0] = 10;
        query[1] = 1;

        List<ScoredChunk> vector = index.vectorSearch(query, 15, prefixes("rare/"));
        List<ScoredChunk> keyword = index.keywordSearch("common words", 15, prefixes("rare/"));

        assertEquals(15, vector.size());
        assertTrue(vector.stream().allMatch(h -> h.chunk().docId().startsWith("rare/")));
        assertEquals(15, keyword.size());
        assertTrue(keyword.stream().allMatch(h -> h.chunk().docId().startsWith("rare/")));
    }

    @Test
    public void vectorSearchOrdersByCosineSimilarity() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "h", List.of(
                        chunk("a.md", 0, "orthogonal"), chunk("a.md", 1, "identical"), chunk("a.md", 2, "diagonal")),
                List.of(new float[]{0, 1, 0}, new float[]{2, 0, 0}, new float[]{1, 1, 0}));

        List<ScoredChunk> hits = index.vectorSearch(new float[]{1, 0, 0}, 3, SearchFilter.none());

        assertEquals(List.of("a.md#1", "a.md#2", "a.md#0"), ids(hits));
        assertEquals(1.0, hits.get(0).score(), 1e-5);
        assertEquals((1 + Math.sqrt(0.5)) / 2, hits.get(1).score(), 1e-5);
        assertEquals(0.5, hits.get(2).score(), 1e-5);
        assertEquals(hits.get(0).score(), hits.get(0).signals().get(ScoredChunk.VECTOR));
        assertEquals(List.of("a.md#1"), ids(index.vectorSearch(new float[]{1, 0, 0}, 1, SearchFilter.none())));
    }

    @Test
    public void chunksWithoutUsableVectorAreKeywordOnly() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "h", List.of(
                        chunk("a.md", 0, "has vector"), chunk("a.md", 1, "null vector"), chunk("a.md", 2, "zero vector")),
                Arrays.asList(new float[]{1, 0, 0}, null, new float[]{0, 0, 0}));

        assertEquals(List.of("a.md#0"), ids(index.vectorSearch(new float[]{1, 1, 1}, 5, SearchFilter.none())));
        assertEquals(List.of("a.md#2"), ids(index.keywordSearch("zero", 5, SearchFilter.none())));
        assertTrue(index.vectorSearch(new float[]{0, 0, 0}, 5, SearchFilter.none()).isEmpty());
        assertTrue(index.vectorSearch(new float[]{1, 0, 0}, 0, SearchFilter.none()).isEmpty());

        index.upsert("b.md", "h", List.of(chunk("b.md", 0, "no vectors list")), null);
        assertTrue(index.get("b.md#0").isPresent());
    }

    @Test
    public void reopenWithOtherDimensionsIsRejected() {
        ChunkIndex first = open(3);
        first.upsert("a.md", "h", List.of(chunk("a.md", 0, "persistent text")), List.<float[]>of(new float[]{1, 0, 0}));
        first.close();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> index(4));
        assertTrue(e.getMessage().contains("3"), e.getMessage());

        ChunkIndex reopened = index(3);
        assertEquals(Optional.of("h"), reopened.contentHash("a.md"));
        assertEquals(List.of("a.md#0"), ids(reopened.vectorSearch(new float[]{1, 0, 0}, 5, SearchFilter.none())));
        assertEquals(List.of("a.md#0"), ids(reopened.keywordSearch("persistent", 5, SearchFilter.none())));
    }

    @Test
    public void invalidUpsertsAreRejectedWithoutPartialWrites() {
        ChunkIndex index = index(3);
        index.upsert("a.md", "v1", List.of(chunk("a.md", 0, "original")), nulls(1));

        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x")), List.<float[]>of(new float[]{1, 0})));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x"), chunk("a.md", 1, "y")), nulls(1)));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("other.md", 0, "x")), nulls(1)));
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2",
                List.of(chunk("a.md", 0, "x"), chunk("a.md", 0, "y")), nulls(2)));
        Chunk foreignId = new Chunk("b.md#0", "a.md", "A", null, List.of(), "x", 0, 0, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> index.upsert("a.md", "v2", List.of(foreignId), nulls(1)));
        assertThrows(IllegalArgumentException.class, () -> index.vectorSearch(new float[]{1, 0}, 3, SearchFilter.none()));

        assertEquals(Optional.of("v1"), index.contentHash("a.md"));
        assertEquals("original", index.get("a.md#0").orElseThrow().text());
        assertEquals(1, index.size());
    }

    @Test
    public void closeIsIdempotentAndBlocksFurtherUse() {
        ChunkIndex index = index(3);
        index.close();
        index.close();

        assertThrows(IllegalStateException.class, index::size);
        assertThrows(IllegalStateException.class, () -> index.upsert("a.md", "h", List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> index.delete("a.md"));
    }

    @Test
    public void concurrentWritersAndReadersNeverSeeMixedVersions() throws Exception {
        ChunkIndex index = index(3);
        index.upsert("a.md", "v0", versionChunks(0), nulls(3));
        AtomicBoolean done = new AtomicBoolean();
        AtomicReference<String> violation = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread reader = new Thread(() -> {
            while (!done.get() && violation.get() == null) {
                List<ScoredChunk> hits = index.keywordSearch("marker", 10, SearchFilter.none());
                Set<String> versions = new HashSet<>();
                hits.forEach(h -> versions.add(h.chunk().text().split(" ")[1]));
                if (hits.size() != 3 || versions.size() != 1) {
                    violation.set("hits=" + hits.size() + " versions=" + versions);
                }
            }
        });
        List<Thread> writers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            int base = w * 1000;
            writers.add(new Thread(() -> {
                try {
                    for (int v = 1; v <= 25; v++) {
                        index.upsert("a.md", "v" + (base + v), versionChunks(base + v), nulls(3));
                    }
                } catch (Throwable t) {
                    failure.set(t);
                }
            }));
        }
        reader.start();
        writers.forEach(Thread::start);
        for (Thread writer : writers) {
            writer.join();
        }
        done.set(true);
        reader.join();

        assertNull(failure.get());
        assertNull(violation.get());
        assertEquals(3, index.size());
        String hash = index.contentHash("a.md").orElseThrow();
        assertTrue(hash.equals("v25") || hash.equals("v1025"), hash);
    }

    @Test
    public void nullArgumentsAreRejected() {
        ChunkIndex index = index(3);

        assertThrows(NullPointerException.class, () -> index.upsert(null, "h", List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> index.get(null));
        assertThrows(NullPointerException.class, () -> index.vectorSearch(null, 3, SearchFilter.none()));
        assertThrows(NullPointerException.class, () -> index.contentHash(null));
    }

    private static List<Chunk> versionChunks(int version) {
        List<Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            chunks.add(chunk("a.md", i, "marker v" + version + " part" + i));
        }
        return chunks;
    }

    /** Four documents mentioning "guide" with vectors near {@code [1, 0, 0]}. */
    private static ChunkIndex populateTeams(ChunkIndex index) {
        index.upsert("team-a/guide.md", "h", List.of(chunk("team-a/guide.md", 0, "team a guide",
                Map.of("team", "a", "level", "public"))), List.<float[]>of(new float[]{1, 0, 0}));
        index.upsert("team-a/sub/deep.md", "h", List.of(chunk("team-a/sub/deep.md", 0, "deep guide",
                Map.of("team", "a", "level", "secret"))), List.<float[]>of(new float[]{1, 0.1f, 0}));
        index.upsert("team-b/guide.md", "h", List.of(chunk("team-b/guide.md", 0, "team b guide",
                Map.of("team", "b", "level", "public"))), List.<float[]>of(new float[]{1, 0.2f, 0}));
        index.upsert("team-ab.md", "h", List.of(chunk("team-ab.md", 0, "shared guide",
                Map.of("team", "ab", "level", "public"))), List.<float[]>of(new float[]{1, 0.3f, 0}));
        return index;
    }
}

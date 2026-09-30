package io.github.qwzhang01.agent.rag.retrieve;

import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.Retriever.Result;
import io.github.qwzhang01.agent.rag.index.FakeEmbeddingClient;
import io.github.qwzhang01.agent.rag.index.LuceneChunkIndex;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import io.github.qwzhang01.agent.rag.retrieve.HybridRetriever.Mode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static io.github.qwzhang01.agent.rag.index.TestChunks.chunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrieverTest {

    private static final int DIMS = 64;

    private LuceneChunkIndex index;
    private FakeEmbeddingClient embedder;

    @BeforeEach
    void setUp() {
        index = LuceneChunkIndex.inMemory(DIMS);
        embedder = new FakeEmbeddingClient(DIMS);
        List<Chunk> chunks = List.of(
                chunk("kb", 0, "citation tracing links each answer sentence to a chunk", Map.of("lang", "en")),
                chunk("kb", 1, "hybrid retrieval fuses bm25 and vector rankings", Map.of("lang", "en")),
                chunk("kb", 2, "引用溯源的实现依赖稳定的编号", Map.of("lang", "zh")),
                chunk("kb", 3, "tomatoes need sunlight and water", Map.of("lang", "en")));
        index.upsert("kb", "h", chunks, embedder.embedAll(chunks.stream().map(Chunk::contextualText).toList()));
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    private static List<String> ids(List<ScoredChunk> hits) {
        return hits.stream().map(h -> h.chunk().chunkId()).toList();
    }

    @Test
    void hybridReportsAllStagesAndReturnsFusedList() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        Result result = retriever.retrieveWithStages("citation tracing", 2, Map.of());

        assertEquals(Mode.HYBRID, retriever.mode());
        assertEquals(Set.of(ScoredChunk.BM25, ScoredChunk.VECTOR, ScoredChunk.RRF), result.stages().keySet());
        assertEquals(result.stages().get(ScoredChunk.RRF), result.hits());
        assertTrue(result.degradations().isEmpty());
        assertEquals("kb#0", result.hits().get(0).chunk().chunkId());
        assertTrue(result.hits().size() <= 2);
        Map<String, Double> signals = result.hits().get(0).signals();
        assertTrue(signals.keySet().containsAll(Set.of(ScoredChunk.BM25, ScoredChunk.VECTOR, ScoredChunk.RRF)));
        assertEquals(result.hits(), retriever.retrieve("citation tracing", 2, Map.of()));
    }

    @Test
    void hybridPoolsAtLeastTopKCandidates() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).candidatePoolSize(1).build();

        Result result = retriever.retrieveWithStages("citation tracing", 3, Map.of());

        assertEquals(3, result.stages().get(ScoredChunk.VECTOR).size());
    }

    @Test
    void embeddingFailureDegradesToKeyword() {
        embedder.failing(true);
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        Result result = retriever.retrieveWithStages("citation tracing", 3, Map.of());

        assertEquals(List.of(ScoredChunk.VECTOR), result.degradations());
        assertEquals(Set.of(ScoredChunk.BM25), result.stages().keySet());
        assertEquals(List.of("kb#0"), ids(result.hits()));
    }

    @Test
    void vectorSearchFailureDegradesToKeyword() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(new FakeEmbeddingClient(DIMS + 1)).build();

        Result result = retriever.retrieveWithStages("citation", 3, Map.of());

        assertEquals(List.of(ScoredChunk.VECTOR), result.degradations());
        assertEquals(List.of("kb#0"), ids(result.hits()));
    }

    @Test
    void keywordModeRunsBm25Only() {
        HybridRetriever retriever = HybridRetriever.builder(index).build();

        Result result = retriever.retrieveWithStages("引用溯源", 5, Map.of());

        assertEquals(Mode.KEYWORD, retriever.mode());
        assertEquals(Set.of(ScoredChunk.BM25), result.stages().keySet());
        assertEquals(List.of("kb#2"), ids(result.hits()));
        assertEquals(0, embedder.singleCalls());
    }

    @Test
    void vectorModeRunsVectorOnly() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).mode(Mode.VECTOR).build();

        Result result = retriever.retrieveWithStages("tomatoes sunlight", 1, Map.of());

        assertEquals(Set.of(ScoredChunk.VECTOR), result.stages().keySet());
        assertEquals(List.of("kb#3"), ids(result.hits()));
        assertTrue(result.degradations().isEmpty());
    }

    @Test
    void vectorModeDegradesToKeywordWhenEmbeddingFails() {
        embedder.failing(true);
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).mode(Mode.VECTOR).build();

        Result result = retriever.retrieveWithStages("tomatoes", 3, Map.of());

        assertEquals(List.of(ScoredChunk.VECTOR), result.degradations());
        assertEquals(Set.of(ScoredChunk.BM25), result.stages().keySet());
        assertEquals(List.of("kb#3"), ids(result.hits()));
    }

    @Test
    void keywordFailureInHybridDegradesToVector() {
        ChunkIndex keywordBroken = new FailingKeywordIndex(index);
        HybridRetriever retriever = HybridRetriever.builder(keywordBroken).embeddings(embedder).build();

        Result result = retriever.retrieveWithStages("tomatoes sunlight", 1, Map.of());

        assertEquals(List.of(ScoredChunk.BM25), result.degradations());
        assertEquals(Set.of(ScoredChunk.VECTOR), result.stages().keySet());
        assertEquals(List.of("kb#3"), ids(result.hits()));
    }

    @Test
    void throwsWhenNoStageCanRun() {
        embedder.failing(true);
        HybridRetriever retriever = HybridRetriever.builder(new FailingKeywordIndex(index)).embeddings(embedder).build();

        assertThrows(IllegalStateException.class, () -> retriever.retrieveWithStages("anything", 3, Map.of()));
    }

    @Test
    void filtersApplyToEveryStage() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        Result result = retriever.retrieveWithStages("citation 引用溯源", 5, Map.of("lang", "zh"));

        assertEquals(List.of("kb#2"), ids(result.stages().get(ScoredChunk.BM25)));
        assertEquals(List.of("kb#2"), ids(result.stages().get(ScoredChunk.VECTOR)));
        assertEquals(List.of("kb#2"), ids(result.hits()));
    }

    @Test
    void blankQueryOrZeroTopKReturnsEmptyResult() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        assertTrue(retriever.retrieveWithStages(" ", 5, Map.of()).hits().isEmpty());
        assertTrue(retriever.retrieveWithStages(null, 5, Map.of()).stages().isEmpty());
        assertTrue(retriever.retrieve("citation", 0, (Map<String, String>) null).isEmpty());
        assertEquals(0, embedder.singleCalls());
    }

    @Test
    void nullFiltersAreTreatedAsNone() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        assertEquals("kb#0", retriever.retrieve("citation tracing", 3, (Map<String, String>) null).get(0).chunk().chunkId());
    }

    @Test
    void customFusionStrategyIsUsed() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder)
                .fusion((rankings, topK) -> rankings.get(1).subList(0, 1)).build();

        Result result = retriever.retrieveWithStages("citation tracing", 3, Map.of());

        assertEquals(result.stages().get(ScoredChunk.VECTOR).subList(0, 1), result.hits());
    }

    @Test
    void builderValidatesConfiguration() {
        assertThrows(NullPointerException.class, () -> HybridRetriever.builder(null));
        assertThrows(IllegalArgumentException.class, () -> HybridRetriever.builder(index).mode(Mode.VECTOR).build());
        assertThrows(IllegalArgumentException.class, () -> HybridRetriever.builder(index).mode(Mode.HYBRID).build());
        assertThrows(IllegalArgumentException.class, () -> HybridRetriever.builder(index).candidatePoolSize(0));
        assertEquals(Mode.KEYWORD, HybridRetriever.builder(index).embeddings(embedder).mode(Mode.KEYWORD).build().mode());
    }

    @Test
    void docIdPrefixesRestrictEveryModeAndStage() {
        List<Chunk> other = List.of(chunk("team-b/kb", 0, "citation tracing for team b", Map.of("lang", "en")));
        index.upsert("team-b/kb", "h", other, embedder.embedAll(other.stream().map(Chunk::contextualText).toList()));
        SearchFilter onlyB = SearchFilter.none().withDocIdPrefixes(List.of("team-b/"));

        Result hybrid = HybridRetriever.builder(index).embeddings(embedder).build()
                .retrieveWithStages("citation tracing", 5, onlyB);
        List<ScoredChunk> keyword = HybridRetriever.builder(index).mode(Mode.KEYWORD).build()
                .retrieve("citation tracing", 5, onlyB);
        List<ScoredChunk> vector = HybridRetriever.builder(index).embeddings(embedder).mode(Mode.VECTOR).build()
                .retrieve("citation tracing", 5, onlyB);

        assertEquals(List.of("team-b/kb#0"), ids(hybrid.hits()));
        hybrid.stages().values().forEach(stage -> assertEquals(List.of("team-b/kb#0"), ids(stage)));
        assertEquals(List.of("team-b/kb#0"), ids(keyword));
        assertEquals(List.of("team-b/kb#0"), ids(vector));
        assertEquals(List.of("kb#0"), ids(HybridRetriever.builder(index).embeddings(embedder).build()
                .retrieve("citation tracing", 1, SearchFilter.of(Map.of("lang", "en"))
                        .withDocIdPrefixes(List.of("kb")))));
    }

    @Test
    void emptyAllowedSetSkipsTheIndexAndEmbedder() {
        HybridRetriever retriever = HybridRetriever.builder(index).embeddings(embedder).build();

        Result result = retriever.retrieveWithStages("citation", 5, SearchFilter.none().withDocIdPrefixes(List.of()));

        assertTrue(result.hits().isEmpty());
        assertEquals(0, embedder.singleCalls());
    }

    @Test
    void indexWithoutPrefixSupportFailsClosed() {
        ChunkIndex legacy = new FailingKeywordIndex(index) {
            @Override
            public List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters) {
                return index.keywordSearch(query, topK, filters);
            }
        };
        HybridRetriever retriever = HybridRetriever.builder(legacy).embeddings(embedder).build();
        SearchFilter restricted = SearchFilter.none().withDocIdPrefixes(List.of("kb"));

        assertThrows(UnsupportedOperationException.class, () -> retriever.retrieveWithStages("citation", 5, restricted));
        assertEquals("kb#0", retriever.retrieve("citation tracing", 3, SearchFilter.of(Map.of("lang", "en")))
                .get(0).chunk().chunkId());
    }

    private static class FailingKeywordIndex implements ChunkIndex {
        private final ChunkIndex delegate;

        FailingKeywordIndex(ChunkIndex delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ScoredChunk> keywordSearch(String query, int topK, Map<String, String> filters) {
            throw new IllegalStateException("keyword index corrupted");
        }

        @Override
        public List<ScoredChunk> vectorSearch(float[] queryVector, int topK, Map<String, String> filters) {
            return delegate.vectorSearch(queryVector, topK, filters);
        }

        @Override
        public void upsert(String docId, String contentHash, List<Chunk> chunks, List<float[]> vectors) {
            delegate.upsert(docId, contentHash, chunks, vectors);
        }

        @Override
        public void delete(String docId) {
            delegate.delete(docId);
        }

        @Override
        public Optional<String> contentHash(String docId) {
            return delegate.contentHash(docId);
        }

        @Override
        public Set<String> docIds() {
            return delegate.docIds();
        }

        @Override
        public Optional<Chunk> get(String chunkId) {
            return delegate.get(chunkId);
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public void close() {
        }
    }
}

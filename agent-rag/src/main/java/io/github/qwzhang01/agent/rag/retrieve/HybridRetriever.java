package io.github.qwzhang01.agent.rag.retrieve;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.rag.ChunkIndex;
import io.github.qwzhang01.agent.rag.FusionStrategy;
import io.github.qwzhang01.agent.rag.Retriever;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * First-stage recall over a {@link ChunkIndex}: BM25, vector, or both fused.
 * <p>
 * In {@link Mode#HYBRID} each sub-retriever fetches {@code max(candidatePoolSize, topK)}
 * candidates and the {@link FusionStrategy} (default {@link ReciprocalRankFusion}) cuts the
 * fused list to {@code topK}. Stages reported: {@code bm25}, {@code vector}, {@code rrf}, only
 * those that ran.
 * <p>
 * Degradation: when embedding the query or the vector search fails, the call falls back to
 * BM25 and reports {@code vector} in {@link Result#degradations()}. In hybrid mode a failing
 * BM25 search likewise falls back to vector and reports {@code bm25}. The call throws only
 * when no stage can run.
 */
public final class HybridRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(HybridRetriever.class);

    public static final int DEFAULT_CANDIDATE_POOL_SIZE = 50;

    /** Which sub-retrievers run. */
    public enum Mode {
        KEYWORD,
        VECTOR,
        HYBRID
    }

    private final ChunkIndex index;
    private final EmbeddingClient embeddings;
    private final FusionStrategy fusion;
    private final Mode mode;
    private final int candidatePoolSize;

    private HybridRetriever(Builder b) {
        this.index = Objects.requireNonNull(b.index, "index");
        this.embeddings = b.embeddings;
        this.fusion = b.fusion == null ? new ReciprocalRankFusion() : b.fusion;
        this.mode = b.mode == null ? (embeddings == null ? Mode.KEYWORD : Mode.HYBRID) : b.mode;
        if (mode != Mode.KEYWORD && embeddings == null) {
            throw new IllegalArgumentException(mode + " mode requires an EmbeddingClient");
        }
        this.candidatePoolSize = b.candidatePoolSize;
    }

    public static Builder builder(ChunkIndex index) {
        return new Builder(index);
    }

    public Mode mode() {
        return mode;
    }

    @Override
    public List<ScoredChunk> retrieve(String query, int topK, Map<String, String> filters) {
        return retrieveWithStages(query, topK, filters).hits();
    }

    @Override
    public Result retrieveWithStages(String query, int topK, Map<String, String> filters) {
        if (query == null || query.isBlank() || topK <= 0) {
            return new Result(List.of(), Map.of(), List.of());
        }
        Map<String, String> safeFilters = filters == null ? Map.of() : filters;
        return switch (mode) {
            case KEYWORD -> keywordOnly(query, topK, safeFilters, List.of());
            case VECTOR -> vectorOnly(query, topK, safeFilters);
            case HYBRID -> hybrid(query, topK, safeFilters);
        };
    }

    private Result keywordOnly(String query, int topK, Map<String, String> filters, List<String> degradations) {
        List<ScoredChunk> bm25 = index.keywordSearch(query, topK, filters);
        return new Result(bm25, Map.of(ScoredChunk.BM25, bm25), degradations);
    }

    private Result vectorOnly(String query, int topK, Map<String, String> filters) {
        List<ScoredChunk> vector = tryVectorSearch(query, topK, filters);
        if (vector == null) {
            return keywordOnly(query, topK, filters, List.of(ScoredChunk.VECTOR));
        }
        return new Result(vector, Map.of(ScoredChunk.VECTOR, vector), List.of());
    }

    private Result hybrid(String query, int topK, Map<String, String> filters) {
        int pool = Math.max(candidatePoolSize, topK);
        List<ScoredChunk> vector = tryVectorSearch(query, pool, filters);
        List<ScoredChunk> bm25;
        try {
            bm25 = index.keywordSearch(query, pool, filters);
        } catch (RuntimeException e) {
            if (vector == null) {
                throw e;
            }
            log.warn("Keyword search failed; retrieval degrades to vector-only: {}", e.toString());
            List<ScoredChunk> hits = vector.subList(0, Math.min(topK, vector.size()));
            return new Result(hits, Map.of(ScoredChunk.VECTOR, vector), List.of(ScoredChunk.BM25));
        }
        if (vector == null) {
            List<ScoredChunk> hits = bm25.subList(0, Math.min(topK, bm25.size()));
            return new Result(hits, Map.of(ScoredChunk.BM25, bm25), List.of(ScoredChunk.VECTOR));
        }
        List<ScoredChunk> fused = fusion.fuse(List.of(bm25, vector), topK);
        Map<String, List<ScoredChunk>> stages = new LinkedHashMap<>();
        stages.put(ScoredChunk.BM25, bm25);
        stages.put(ScoredChunk.VECTOR, vector);
        stages.put(ScoredChunk.RRF, fused);
        return new Result(fused, stages, List.of());
    }

    /** Returns null when the query cannot be embedded or the vector search fails. */
    private List<ScoredChunk> tryVectorSearch(String query, int topK, Map<String, String> filters) {
        try {
            float[] vector = embeddings.embed(query);
            if (vector == null) {
                throw new IllegalStateException("embedding provider returned null");
            }
            return index.vectorSearch(vector, topK, filters);
        } catch (RuntimeException e) {
            log.warn("Vector retrieval failed; degrading to keyword search: {}", e.toString());
            return null;
        }
    }

    /** Builder; only the index is required. */
    public static final class Builder {
        private final ChunkIndex index;
        private EmbeddingClient embeddings;
        private FusionStrategy fusion;
        private Mode mode;
        private int candidatePoolSize = DEFAULT_CANDIDATE_POOL_SIZE;

        private Builder(ChunkIndex index) {
            this.index = Objects.requireNonNull(index, "index");
        }

        /** Query embedder; required for {@link Mode#VECTOR} and {@link Mode#HYBRID}. */
        public Builder embeddings(EmbeddingClient embeddings) {
            this.embeddings = embeddings;
            return this;
        }

        /** Default {@link ReciprocalRankFusion} with {@code k = 60}. */
        public Builder fusion(FusionStrategy fusion) {
            this.fusion = fusion;
            return this;
        }

        /** Default {@link Mode#HYBRID} when an embedder is set, otherwise {@link Mode#KEYWORD}. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        /** Candidates per sub-retriever in hybrid mode; default {@value #DEFAULT_CANDIDATE_POOL_SIZE}. */
        public Builder candidatePoolSize(int candidatePoolSize) {
            if (candidatePoolSize < 1) {
                throw new IllegalArgumentException("candidatePoolSize must be >= 1: " + candidatePoolSize);
            }
            this.candidatePoolSize = candidatePoolSize;
            return this;
        }

        public HybridRetriever build() {
            return new HybridRetriever(this);
        }
    }
}

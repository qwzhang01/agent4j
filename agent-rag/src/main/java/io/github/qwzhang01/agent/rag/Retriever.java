package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;
import java.util.Map;

/** First-stage recall. Implementations: keyword-only, vector-only, hybrid. */
public interface Retriever {

    /**
     * @param query   retrieval query (already rewritten)
     * @param topK    number of candidates to return
     * @param filters exact-match metadata filters
     * @return candidates, best first
     */
    List<ScoredChunk> retrieve(String query, int topK, Map<String, String> filters);

    /**
     * Same as {@link #retrieve} but also reports each underlying ranking, keyed by stage
     * name, for tracing. Default: the final list under {@code "retrieve"}.
     */
    default Result retrieveWithStages(String query, int topK, Map<String, String> filters) {
        List<ScoredChunk> hits = retrieve(query, topK, filters);
        return new Result(hits, Map.of("retrieve", hits), List.of());
    }

    /**
     * @param hits         final fused ranking
     * @param stages       per-stage rankings (e.g. {@code bm25}, {@code vector}, {@code rrf})
     * @param degradations stages that failed and were skipped (e.g. {@code vector} when embedding is down)
     */
    record Result(List<ScoredChunk> hits, Map<String, List<ScoredChunk>> stages, List<String> degradations) {
        public Result {
            hits = List.copyOf(hits);
            stages = Map.copyOf(stages);
            degradations = List.copyOf(degradations);
        }
    }
}

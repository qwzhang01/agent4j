package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SearchFilter;

import java.util.List;
import java.util.Map;

/**
 * First-stage recall. Implementations: keyword-only, vector-only, hybrid.
 * <p>
 * {@link io.github.qwzhang01.agent.rag.pipeline.RagPipeline} calls
 * {@link #retrieveWithStages(String, int, SearchFilter)}. Implementations that can enforce
 * document-id restrictions override it; the default delegates metadata-only filters to the
 * {@code Map} overload and throws {@link UnsupportedOperationException} for a document-id
 * restriction, so an unaware retriever never returns chunks the caller may not see.
 */
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
     * Same as {@link #retrieveWithStages(String, int, Map)} with a filter that may also restrict
     * document ids; null means {@link SearchFilter#none()}. A filter that
     * {@linkplain SearchFilter#matchesNothing() matches nothing} yields an empty result.
     *
     * @throws UnsupportedOperationException when the filter restricts document ids and the
     *                                       implementation does not override this method
     */
    default Result retrieveWithStages(String query, int topK, SearchFilter filter) {
        SearchFilter f = filter == null ? SearchFilter.none() : filter;
        if (f.matchesNothing()) {
            return new Result(List.of(), Map.of(), List.of());
        }
        if (f.restrictsDocIds()) {
            throw new UnsupportedOperationException(getClass().getName()
                    + " does not support document-id restrictions; override retrieveWithStages(String, int, SearchFilter)");
        }
        return retrieveWithStages(query, topK, f.metadata());
    }

    /** Final ranking of {@link #retrieveWithStages(String, int, SearchFilter)}. */
    default List<ScoredChunk> retrieve(String query, int topK, SearchFilter filter) {
        return retrieveWithStages(query, topK, filter).hits();
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

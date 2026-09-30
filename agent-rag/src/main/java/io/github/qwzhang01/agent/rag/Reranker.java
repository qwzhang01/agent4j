package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;

/**
 * Second-stage precision ranking, typically a cross-encoder behind HTTP
 * (cloud API or a self-hosted service). Provider differences live in implementations.
 */
public interface Reranker {

    /**
     * @param query      retrieval query
     * @param candidates first-stage candidates
     * @param topN       number of results to keep
     * @return at most {@code topN} candidates, best first, score signal {@link ScoredChunk#RERANK}
     * @throws RerankException when the provider call fails; callers decide whether to degrade
     */
    List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN);

    /**
     * Same as {@link #rerank} but lets a reranker that degrades internally (keeps first-stage order
     * instead of throwing) report it, so callers can trace the degradation. Default: a successful
     * outcome wrapping {@link #rerank}.
     *
     * @throws RerankException when the provider call fails or {@link #rerank} returns null
     */
    default Outcome rerankWithOutcome(String query, List<ScoredChunk> candidates, int topN) {
        List<ScoredChunk> results = rerank(query, candidates, topN);
        if (results == null) {
            throw new RerankException("reranker returned null");
        }
        return new Outcome(results, false, null);
    }

    /**
     * @param results  reranked (or fallback) results, best first
     * @param degraded true when reranking failed and first-stage order was kept
     * @param error    failure description when degraded, null otherwise
     */
    record Outcome(List<ScoredChunk> results, boolean degraded, String error) {
        public Outcome {
            results = results == null ? List.of() : List.copyOf(results);
        }
    }
}

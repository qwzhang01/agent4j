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
}

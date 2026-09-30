package io.github.qwzhang01.agent.rag.rerank;

import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;
import java.util.Objects;

/** Keeps first-stage order: returns the first {@code topN} candidates unchanged. */
public final class NoOpReranker implements Reranker {

    public static final NoOpReranker INSTANCE = new NoOpReranker();

    @Override
    public List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, int topN) {
        Objects.requireNonNull(candidates, "candidates");
        return head(candidates, topN);
    }

    static List<ScoredChunk> head(List<ScoredChunk> candidates, int topN) {
        if (topN <= 0) {
            return List.of();
        }
        return List.copyOf(candidates.size() <= topN ? candidates : candidates.subList(0, topN));
    }
}

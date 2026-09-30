package io.github.qwzhang01.agent.rag;

import io.github.qwzhang01.agent.rag.model.ScoredChunk;

import java.util.List;

/** Merges several rankings of the same corpus into one. */
public interface FusionStrategy {

    /**
     * @param rankings each list best first; the same chunk may appear in several lists
     * @param topK     size of the fused list
     * @return fused list, best first, each chunk once, carrying the signals of every input list it appeared in
     */
    List<ScoredChunk> fuse(List<List<ScoredChunk>> rankings, int topK);
}

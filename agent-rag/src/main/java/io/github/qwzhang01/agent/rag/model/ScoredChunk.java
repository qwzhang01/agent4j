package io.github.qwzhang01.agent.rag.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A chunk with the score of the stage that produced this ranking.
 *
 * @param chunk   the chunk
 * @param score   score of the current stage (higher is better; scales differ across stages)
 * @param signals per-stage scores accumulated along the pipeline, keyed by {@code bm25}, {@code vector},
 *                {@code rrf}, {@code rerank}; kept for traces and debugging
 */
public record ScoredChunk(Chunk chunk, double score, Map<String, Double> signals) {

    public static final String BM25 = "bm25";
    public static final String VECTOR = "vector";
    public static final String RRF = "rrf";
    public static final String RERANK = "rerank";

    public ScoredChunk {
        Objects.requireNonNull(chunk, "chunk");
        signals = signals == null ? Map.of() : Map.copyOf(signals);
    }

    public static ScoredChunk of(Chunk chunk, String stage, double score) {
        return new ScoredChunk(chunk, score, Map.of(stage, score));
    }

    /** Returns a copy re-scored by {@code stage}, keeping earlier signals. */
    public ScoredChunk withStage(String stage, double newScore) {
        Map<String, Double> merged = new LinkedHashMap<>(signals);
        merged.put(stage, newScore);
        return new ScoredChunk(chunk, newScore, merged);
    }
}

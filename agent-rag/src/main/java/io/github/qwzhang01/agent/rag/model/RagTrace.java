package io.github.qwzhang01.agent.rag.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything needed to replay and debug one RAG call. Emitted once per call to
 * {@code RagTraceListener}s; the framework itself never persists it.
 *
 * @param traceId          unique id of this call
 * @param timestamp        call start
 * @param question         original user question
 * @param rewrittenQuery   query actually used for retrieval
 * @param stageHits        chunk ids with scores per stage, keyed by stage name
 *                         ({@code bm25}, {@code vector}, {@code rrf}, {@code rerank})
 * @param contextChunkIds  chunk ids finally given to the generator, in order
 * @param stageLatencyMs   wall time per stage ({@code rewrite}, {@code retrieve}, {@code rerank},
 *                         {@code generate}, {@code verify}, {@code total})
 * @param promptTokens     generator + verifier + rewriter prompt tokens
 * @param completionTokens generator + verifier + rewriter completion tokens
 * @param refused          generator declined for lack of material
 * @param verdictCounts    number of answer sentences per {@link SupportVerdict}
 * @param degradations     components that failed and were bypassed (e.g. {@code rerank}, {@code vector})
 */
public record RagTrace(
        String traceId,
        Instant timestamp,
        String question,
        String rewrittenQuery,
        Map<String, List<Hit>> stageHits,
        List<String> contextChunkIds,
        Map<String, Long> stageLatencyMs,
        int promptTokens,
        int completionTokens,
        boolean refused,
        Map<SupportVerdict, Integer> verdictCounts,
        List<String> degradations
) {
    public RagTrace {
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(timestamp, "timestamp");
        stageHits = stageHits == null ? Map.of() : Map.copyOf(stageHits);
        contextChunkIds = contextChunkIds == null ? List.of() : List.copyOf(contextChunkIds);
        stageLatencyMs = stageLatencyMs == null ? Map.of() : Map.copyOf(stageLatencyMs);
        verdictCounts = verdictCounts == null ? Map.of() : Map.copyOf(verdictCounts);
        degradations = degradations == null ? List.of() : List.copyOf(degradations);
    }

    /** A chunk id and its score at one stage. */
    public record Hit(String chunkId, double score) {
    }
}

package io.github.qwzhang01.agent.rag.pipeline;

import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.RagTrace;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Mutable bookkeeping of one {@link RagPipeline#ask} call, turned into a {@link RagTrace} at the end. */
final class CallRecord {

    private final Map<String, Long> latency = new LinkedHashMap<>();
    private final List<String> degradations = new ArrayList<>();
    private final Map<String, List<RagTrace.Hit>> stageHits = new LinkedHashMap<>();
    private int promptTokens;
    private int completionTokens;

    void tokens(int prompt, int completion) {
        promptTokens += prompt;
        completionTokens += completion;
    }

    void degraded(String component) {
        degradations.add(component);
    }

    void degraded(List<String> components) {
        degradations.addAll(components);
    }

    void stage(String name, List<ScoredChunk> hits) {
        stageHits.put(name, hits.stream().map(c -> new RagTrace.Hit(c.chunk().chunkId(), c.score())).toList());
    }

    void latency(String stage, long millis) {
        latency.put(stage, millis);
    }

    RagTrace toTrace(long startMillis, String question, String retrievalQuery, List<ScoredChunk> contexts,
                     boolean refused, List<AnswerSentence> sentences) {
        Map<SupportVerdict, Integer> verdicts = new EnumMap<>(SupportVerdict.class);
        sentences.forEach(s -> verdicts.merge(s.verdict(), 1, Integer::sum));
        return new RagTrace(
                UUID.randomUUID().toString(),
                Instant.ofEpochMilli(startMillis),
                question,
                retrievalQuery,
                stageHits,
                contexts.stream().map(c -> c.chunk().chunkId()).toList(),
                latency,
                promptTokens,
                completionTokens,
                refused,
                verdicts,
                degradations);
    }
}

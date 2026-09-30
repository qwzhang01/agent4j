package io.github.qwzhang01.agent.rag.pipeline;

import io.github.qwzhang01.agent.rag.AnswerGenerator;
import io.github.qwzhang01.agent.rag.CitationVerifier;
import io.github.qwzhang01.agent.rag.QueryRewriter;
import io.github.qwzhang01.agent.rag.RagTraceListener;
import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.Retriever;
import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.GeneratedAnswer;
import io.github.qwzhang01.agent.rag.model.RagAnswer;
import io.github.qwzhang01.agent.rag.model.RagQuery;
import io.github.qwzhang01.agent.rag.model.RagTrace;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rewrite, retrieve, rerank, generate, verify, trace.
 * <p>
 * Failure policy: rewrite, rerank and verify failures are bypassed and recorded as
 * degradations. A generator failure returns the retrieved passages instead of an answer
 * (degradation {@code generate}). Only a retrieval failure fails the call with
 * {@link RagException}, because answering "no material" would then be a lie.
 */
public final class RagPipeline {

    private static final Logger log = LoggerFactory.getLogger(RagPipeline.class);

    private final QueryRewriter rewriter;
    private final Retriever retriever;
    private final Reranker reranker;
    private final AnswerGenerator generator;
    private final CitationVerifier verifier;
    private final List<RagTraceListener> listeners;
    private final int candidateK;
    private final int contextK;
    private final Function<List<ScoredChunk>, String> generationFallback;
    private final Clock clock;

    private RagPipeline(Builder b) {
        this.rewriter = b.rewriter;
        this.retriever = Objects.requireNonNull(b.retriever, "retriever");
        this.reranker = b.reranker;
        this.generator = Objects.requireNonNull(b.generator, "generator");
        this.verifier = b.verifier;
        this.listeners = List.copyOf(b.listeners);
        this.candidateK = b.candidateK;
        this.contextK = b.contextK;
        this.generationFallback = b.generationFallback;
        this.clock = b.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RagAnswer ask(RagQuery query) {
        Objects.requireNonNull(query, "query");
        long start = clock.millis();
        Map<String, Long> latency = new LinkedHashMap<>();
        List<String> degradations = new ArrayList<>();
        int promptTokens = 0;
        int completionTokens = 0;

        long t = clock.millis();
        String retrievalQuery = query.question();
        if (rewriter != null && !query.history().isEmpty()) {
            try {
                QueryRewriter.Rewrite rewrite = rewriter.rewrite(query.question(), query.history());
                retrievalQuery = rewrite.query();
                promptTokens += rewrite.promptTokens();
                completionTokens += rewrite.completionTokens();
                if (rewrite.degraded()) {
                    degradations.add("rewrite");
                }
            } catch (RuntimeException e) {
                log.warn("Query rewrite failed, retrieving with the original question: {}", e.getMessage());
                degradations.add("rewrite");
            }
        }
        latency.put("rewrite", clock.millis() - t);

        t = clock.millis();
        Retriever.Result retrieved;
        try {
            retrieved = retriever.retrieveWithStages(retrievalQuery, candidateK, query.filters());
        } catch (RuntimeException e) {
            throw new RagException("Retrieval failed: " + e.getMessage(), e);
        }
        degradations.addAll(retrieved.degradations());
        latency.put("retrieve", clock.millis() - t);

        Map<String, List<RagTrace.Hit>> stageHits = new LinkedHashMap<>();
        retrieved.stages().forEach((stage, hits) -> stageHits.put(stage, toHits(hits)));

        t = clock.millis();
        List<ScoredChunk> contexts = topN(retrieved.hits(), contextK);
        if (reranker != null && !retrieved.hits().isEmpty()) {
            try {
                contexts = reranker.rerank(retrievalQuery, retrieved.hits(), contextK);
                stageHits.put(ScoredChunk.RERANK, toHits(contexts));
            } catch (RuntimeException e) {
                log.warn("Rerank failed, using first-stage order: {}", e.getMessage());
                degradations.add("rerank");
            }
        }
        latency.put("rerank", clock.millis() - t);

        t = clock.millis();
        GeneratedAnswer generated;
        try {
            generated = generator.generate(query.question(), query.history(), contexts);
            promptTokens += generated.promptTokens();
            completionTokens += generated.completionTokens();
        } catch (RuntimeException e) {
            log.warn("Generation failed, returning retrieved passages: {}", e.getMessage());
            degradations.add("generate");
            generated = new GeneratedAnswer(generationFallback.apply(contexts), List.of(), false, 0, 0);
        }
        latency.put("generate", clock.millis() - t);

        t = clock.millis();
        List<AnswerSentence> sentences = generated.sentences();
        if (verifier != null && !generated.refused() && !sentences.isEmpty()) {
            Map<String, Chunk> byId = new LinkedHashMap<>();
            contexts.forEach(c -> byId.put(c.chunk().chunkId(), c.chunk()));
            try {
                CitationVerifier.Verification v = verifier.verify(sentences, byId);
                sentences = v.sentences();
                promptTokens += v.promptTokens();
                completionTokens += v.completionTokens();
                if (v.degraded()) {
                    degradations.add("verify");
                }
            } catch (RuntimeException e) {
                log.warn("Verification failed, sentences left unverified: {}", e.getMessage());
                degradations.add("verify");
            }
        }
        latency.put("verify", clock.millis() - t);
        latency.put("total", clock.millis() - start);

        RagTrace trace = new RagTrace(
                UUID.randomUUID().toString(),
                Instant.ofEpochMilli(start),
                query.question(),
                retrievalQuery,
                stageHits,
                contexts.stream().map(c -> c.chunk().chunkId()).toList(),
                latency,
                promptTokens,
                completionTokens,
                generated.refused(),
                countVerdicts(sentences),
                degradations);
        publish(trace);
        return new RagAnswer(generated.rawText(), sentences, contexts, generated.refused(), trace);
    }

    private void publish(RagTrace trace) {
        for (RagTraceListener listener : listeners) {
            try {
                listener.onTrace(trace);
            } catch (RuntimeException e) {
                log.warn("RagTraceListener {} failed: {}", listener.getClass().getName(), e.getMessage());
            }
        }
    }

    private static List<ScoredChunk> topN(List<ScoredChunk> hits, int n) {
        return hits.size() <= n ? hits : hits.subList(0, n);
    }

    private static List<RagTrace.Hit> toHits(List<ScoredChunk> chunks) {
        return chunks.stream().map(c -> new RagTrace.Hit(c.chunk().chunkId(), c.score())).toList();
    }

    private static Map<SupportVerdict, Integer> countVerdicts(List<AnswerSentence> sentences) {
        Map<SupportVerdict, Integer> counts = new EnumMap<>(SupportVerdict.class);
        sentences.forEach(s -> counts.merge(s.verdict(), 1, Integer::sum));
        return counts;
    }

    /** Lists the passages by title and section; used when the generator is unavailable. */
    static String defaultGenerationFallback(List<ScoredChunk> contexts) {
        if (contexts.isEmpty()) {
            return "回答服务暂时不可用，也没有检索到相关资料。";
        }
        StringBuilder sb = new StringBuilder("回答服务暂时不可用。以下是检索到的相关资料：");
        for (ScoredChunk c : contexts) {
            Chunk chunk = c.chunk();
            sb.append("\n- 《").append(chunk.docTitle()).append('》');
            if (!chunk.sectionPath().isEmpty()) {
                sb.append(' ').append(chunk.sectionLabel());
            }
            sb.append(" [").append(chunk.chunkId()).append(']');
        }
        return sb.toString();
    }

    public static final class Builder {
        private QueryRewriter rewriter;
        private Retriever retriever;
        private Reranker reranker;
        private AnswerGenerator generator;
        private CitationVerifier verifier;
        private final List<RagTraceListener> listeners = new ArrayList<>();
        private int candidateK = 30;
        private int contextK = 6;
        private Function<List<ScoredChunk>, String> generationFallback = RagPipeline::defaultGenerationFallback;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /** Optional; without it multi-turn follow-ups are retrieved verbatim. */
        public Builder queryRewriter(QueryRewriter rewriter) {
            this.rewriter = rewriter;
            return this;
        }

        public Builder retriever(Retriever retriever) {
            this.retriever = retriever;
            return this;
        }

        /** Optional; without it the first-stage order is used. */
        public Builder reranker(Reranker reranker) {
            this.reranker = reranker;
            return this;
        }

        public Builder generator(AnswerGenerator generator) {
            this.generator = generator;
            return this;
        }

        /** Optional; without it every sentence stays {@code UNVERIFIED}. */
        public Builder verifier(CitationVerifier verifier) {
            this.verifier = verifier;
            return this;
        }

        public Builder listener(RagTraceListener listener) {
            this.listeners.add(Objects.requireNonNull(listener, "listener"));
            return this;
        }

        /** First-stage candidates handed to the reranker. Default 30. */
        public Builder candidateK(int candidateK) {
            if (candidateK < 1) {
                throw new IllegalArgumentException("candidateK must be >= 1");
            }
            this.candidateK = candidateK;
            return this;
        }

        /** Chunks given to the generator. Default 6. */
        public Builder contextK(int contextK) {
            if (contextK < 1) {
                throw new IllegalArgumentException("contextK must be >= 1");
            }
            this.contextK = contextK;
            return this;
        }

        /** Text returned when the generator fails; receives the chunks that would have been the context. */
        public Builder generationFallback(Function<List<ScoredChunk>, String> fallback) {
            this.generationFallback = Objects.requireNonNull(fallback, "fallback");
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock");
            return this;
        }

        public RagPipeline build() {
            if (contextK > candidateK) {
                throw new IllegalArgumentException("contextK must be <= candidateK");
            }
            return new RagPipeline(this);
        }
    }
}

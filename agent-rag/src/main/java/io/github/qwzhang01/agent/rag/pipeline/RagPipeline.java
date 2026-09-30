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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Rewrite, retrieve, rerank, generate, verify, trace.
 * <p>
 * Failure policy: rewrite, rerank and verify failures are bypassed and recorded as
 * degradations; so is a reranker that reports a degraded {@link Reranker.Outcome}. A generator
 * failure returns the retrieved passages instead of an answer (degradation {@code generate}).
 * Only a retrieval failure fails the call with {@link RagException}, because answering
 * "no material" would then be a lie.
 * <p>
 * {@link RagAnswer#contexts()} and {@link RagTrace#contextChunkIds()} hold the chunks the generator
 * reports it actually showed the model ({@link GeneratedAnswer#usedChunkIds()}).
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

    /** @throws RagException when retrieval fails */
    public RagAnswer ask(RagQuery query) {
        Objects.requireNonNull(query, "query");
        long start = clock.millis();
        CallRecord call = new CallRecord();

        long t = clock.millis();
        String retrievalQuery = rewrite(query, call);
        call.latency("rewrite", clock.millis() - t);

        t = clock.millis();
        Retriever.Result retrieved;
        try {
            retrieved = retriever.retrieveWithStages(retrievalQuery, candidateK, query.filters());
        } catch (RuntimeException e) {
            throw new RagException("Retrieval failed: " + e.getMessage(), e);
        }
        call.degraded(retrieved.degradations());
        retrieved.stages().forEach(call::stage);
        call.latency("retrieve", clock.millis() - t);

        t = clock.millis();
        List<ScoredChunk> contexts = rerank(retrievalQuery, topN(retrieved.hits(), candidateK), call);
        call.latency("rerank", clock.millis() - t);

        t = clock.millis();
        GeneratedAnswer generated;
        try {
            generated = generator.generate(query.question(), query.history(), contexts);
            call.tokens(generated.promptTokens(), generated.completionTokens());
        } catch (RuntimeException e) {
            log.warn("Generation failed, returning retrieved passages: {}", e.getMessage());
            call.degraded("generate");
            generated = new GeneratedAnswer(generationFallback.apply(contexts), List.of(), false, 0, 0);
        }
        List<ScoredChunk> shown = shown(contexts, generated.usedChunkIds());
        call.latency("generate", clock.millis() - t);

        t = clock.millis();
        List<AnswerSentence> sentences = generated.refused() ? List.of() : generated.sentences();
        sentences = verify(sentences, shown, call);
        call.latency("verify", clock.millis() - t);
        call.latency("total", clock.millis() - start);

        RagTrace trace = call.toTrace(start, query.question(), retrievalQuery, shown, generated.refused(), sentences);
        publish(trace);
        return new RagAnswer(generated.rawText(), sentences, shown, generated.refused(), trace);
    }

    private String rewrite(RagQuery query, CallRecord call) {
        if (rewriter == null || query.history().isEmpty()) {
            return query.question();
        }
        try {
            QueryRewriter.Rewrite rewrite = rewriter.rewrite(query.question(), query.history());
            call.tokens(rewrite.promptTokens(), rewrite.completionTokens());
            if (rewrite.query() == null || rewrite.query().isBlank()) {
                log.warn("Query rewrite returned a blank query, retrieving with the original question");
                call.degraded("rewrite");
                return query.question();
            }
            if (rewrite.degraded()) {
                call.degraded("rewrite");
            }
            return rewrite.query();
        } catch (RuntimeException e) {
            log.warn("Query rewrite failed, retrieving with the original question: {}", e.getMessage());
            call.degraded("rewrite");
            return query.question();
        }
    }

    private List<ScoredChunk> rerank(String query, List<ScoredChunk> candidates, CallRecord call) {
        if (reranker == null || candidates.isEmpty()) {
            return topN(candidates, contextK);
        }
        try {
            Reranker.Outcome outcome = Objects.requireNonNull(
                    reranker.rerankWithOutcome(query, candidates, contextK), "reranker returned null");
            if (outcome.degraded()) {
                log.warn("Rerank degraded, using first-stage order: {}", outcome.error());
                call.degraded(ScoredChunk.RERANK);
            } else {
                call.stage(ScoredChunk.RERANK, outcome.results());
            }
            return topN(outcome.results(), contextK);
        } catch (RuntimeException e) {
            log.warn("Rerank failed, using first-stage order: {}", e.getMessage());
            call.degraded(ScoredChunk.RERANK);
            return topN(candidates, contextK);
        }
    }

    private List<AnswerSentence> verify(List<AnswerSentence> sentences, List<ScoredChunk> shown, CallRecord call) {
        if (verifier == null || sentences.isEmpty()) {
            return sentences;
        }
        Map<String, Chunk> byId = new LinkedHashMap<>();
        shown.forEach(c -> byId.put(c.chunk().chunkId(), c.chunk()));
        try {
            CitationVerifier.Verification v = verifier.verify(sentences, byId);
            call.tokens(v.promptTokens(), v.completionTokens());
            if (v.sentences().size() != sentences.size()) {
                throw new IllegalStateException("verifier returned " + v.sentences().size()
                        + " sentences for " + sentences.size());
            }
            if (v.degraded()) {
                call.degraded("verify");
            }
            return v.sentences();
        } catch (RuntimeException e) {
            log.warn("Verification failed, sentences left unverified: {}", e.getMessage());
            call.degraded("verify");
            return sentences;
        }
    }

    private static List<ScoredChunk> shown(List<ScoredChunk> contexts, List<String> usedChunkIds) {
        if (usedChunkIds == null) {
            return contexts;
        }
        Set<String> used = new HashSet<>(usedChunkIds);
        return contexts.stream().filter(c -> used.contains(c.chunk().chunkId())).toList();
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

    public static final class Builder {
        private QueryRewriter rewriter;
        private Retriever retriever;
        private Reranker reranker;
        private AnswerGenerator generator;
        private CitationVerifier verifier;
        private final List<RagTraceListener> listeners = new ArrayList<>();
        private int candidateK = 30;
        private int contextK = 6;
        private Function<List<ScoredChunk>, String> generationFallback = PassageListFallback::render;
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

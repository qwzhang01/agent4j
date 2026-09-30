package io.github.qwzhang01.agent.rag.pipeline;

import io.github.qwzhang01.agent.rag.AnswerGenerator;
import io.github.qwzhang01.agent.rag.CitationVerifier;
import io.github.qwzhang01.agent.rag.QueryRewriter;
import io.github.qwzhang01.agent.rag.RerankException;
import io.github.qwzhang01.agent.rag.Reranker;
import io.github.qwzhang01.agent.rag.Retriever;
import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import io.github.qwzhang01.agent.rag.model.GeneratedAnswer;
import io.github.qwzhang01.agent.rag.model.RagAnswer;
import io.github.qwzhang01.agent.rag.model.RagQuery;
import io.github.qwzhang01.agent.rag.model.RagTrace;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPipelineTest {

    private static Chunk chunk(String id) {
        return new Chunk(id, id.substring(0, id.indexOf('#')), "Doc " + id, "/tmp/" + id,
                List.of("Intro"), "text of " + id, 1, 2, null, Map.of());
    }

    private static List<ScoredChunk> hits(String... ids) {
        List<ScoredChunk> out = new ArrayList<>();
        double score = 1.0;
        for (String id : ids) {
            out.add(ScoredChunk.of(chunk(id), ScoredChunk.RRF, score));
            score -= 0.1;
        }
        return out;
    }

    private static final class StubRetriever implements Retriever {
        String lastQuery;
        RuntimeException failure;
        List<ScoredChunk> result = hits("a.md#0", "a.md#1", "b.md#0");

        @Override
        public List<ScoredChunk> retrieve(String query, int topK, Map<String, String> filters) {
            return retrieveWithStages(query, topK, filters).hits();
        }

        @Override
        public Result retrieveWithStages(String query, int topK, Map<String, String> filters) {
            lastQuery = query;
            if (failure != null) {
                throw failure;
            }
            return new Result(result, Map.of(ScoredChunk.RRF, result), List.of());
        }
    }

    private static final class StubGenerator implements AnswerGenerator {
        List<ScoredChunk> lastContexts;
        boolean fail;

        @Override
        public GeneratedAnswer generate(String question, List<ConversationTurn> history, List<ScoredChunk> contexts) {
            if (fail) {
                throw new IllegalStateException("llm down");
            }
            lastContexts = contexts;
            String id = contexts.get(0).chunk().chunkId();
            return new GeneratedAnswer("answer [" + id + "]",
                    List.of(new AnswerSentence("answer", List.of(id), null, null)), false, 10, 5);
        }
    }

    @Test
    void happyPathRewritesRerankVerifiesAndTraces() {
        StubRetriever retriever = new StubRetriever();
        StubGenerator generator = new StubGenerator();
        AtomicReference<RagTrace> traced = new AtomicReference<>();
        QueryRewriter rewriter = (q, h) -> new QueryRewriter.Rewrite("standalone " + q, 3, 2, false);
        Reranker reranker = (q, c, n) -> List.of(c.get(2).withStage(ScoredChunk.RERANK, 0.9));
        CitationVerifier verifier = (s, byId) -> new CitationVerifier.Verification(
                s.stream().map(x -> x.withVerdict(SupportVerdict.SUPPORTED, "ok")).toList(), 7, 1, false);

        RagAnswer answer = RagPipeline.builder()
                .queryRewriter(rewriter).retriever(retriever).reranker(reranker)
                .generator(generator).verifier(verifier).listener(traced::set)
                .candidateK(10).contextK(2).build()
                .ask(new RagQuery("那怎么做", List.of(ConversationTurn.user("RAG 引用")), Map.of()));

        assertEquals("standalone 那怎么做", retriever.lastQuery);
        assertEquals("b.md#0", generator.lastContexts.get(0).chunk().chunkId());
        assertEquals(SupportVerdict.SUPPORTED, answer.sentences().get(0).verdict());
        RagTrace trace = traced.get();
        assertEquals(answer.trace(), trace);
        assertEquals(20, trace.promptTokens());
        assertEquals(8, trace.completionTokens());
        assertEquals(List.of("b.md#0"), trace.contextChunkIds());
        assertTrue(trace.stageHits().containsKey(ScoredChunk.RERANK));
        assertEquals(1, trace.verdictCounts().get(SupportVerdict.SUPPORTED));
        assertTrue(trace.degradations().isEmpty());
        assertTrue(trace.stageLatencyMs().containsKey("total"));
    }

    @Test
    void noHistorySkipsRewrite() {
        StubRetriever retriever = new StubRetriever();
        QueryRewriter rewriter = (q, h) -> {
            throw new AssertionError("must not be called");
        };
        RagPipeline.builder().queryRewriter(rewriter).retriever(retriever).generator(new StubGenerator())
                .build().ask(RagQuery.of("原问题"));
        assertEquals("原问题", retriever.lastQuery);
    }

    @Test
    void componentFailuresDegrade() {
        StubRetriever retriever = new StubRetriever();
        QueryRewriter rewriter = (q, h) -> {
            throw new IllegalStateException("rewrite down");
        };
        Reranker reranker = (q, c, n) -> {
            throw new RerankException("timeout");
        };
        CitationVerifier verifier = (s, byId) -> {
            throw new IllegalStateException("verify down");
        };
        StubGenerator generator = new StubGenerator();

        RagAnswer answer = RagPipeline.builder()
                .queryRewriter(rewriter).retriever(retriever).reranker(reranker)
                .generator(generator).verifier(verifier).contextK(2).build()
                .ask(new RagQuery("q", List.of(ConversationTurn.user("h")), Map.of()));

        assertEquals("q", retriever.lastQuery);
        assertEquals(List.of("a.md#0", "a.md#1"),
                generator.lastContexts.stream().map(c -> c.chunk().chunkId()).toList());
        assertEquals(SupportVerdict.UNVERIFIED, answer.sentences().get(0).verdict());
        assertEquals(List.of("rewrite", "rerank", "verify"), answer.trace().degradations());
    }

    @Test
    void generatorFailureReturnsPassages() {
        StubGenerator generator = new StubGenerator();
        generator.fail = true;
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever()).generator(generator)
                .contextK(2).build().ask(RagQuery.of("q"));

        assertFalse(answer.refused());
        assertTrue(answer.answer().contains("[a.md#0]"));
        assertTrue(answer.sentences().isEmpty());
        assertEquals(List.of("generate"), answer.trace().degradations());
    }

    @Test
    void retrievalFailureFailsTheCall() {
        StubRetriever retriever = new StubRetriever();
        retriever.failure = new IllegalStateException("index closed");
        RagPipeline pipeline = RagPipeline.builder().retriever(retriever).generator(new StubGenerator()).build();
        assertThrows(RagException.class, () -> pipeline.ask(RagQuery.of("q")));
    }

    @Test
    void listenerFailureIsSwallowed() {
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever()).generator(new StubGenerator())
                .listener(t -> {
                    throw new IllegalStateException("sink down");
                }).build().ask(RagQuery.of("q"));
        assertEquals("answer [a.md#0]", answer.answer());
    }

    @Test
    void fallbackTextWithoutContexts() {
        assertTrue(RagPipeline.defaultGenerationFallback(List.of()).contains("没有检索到"));
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> RagPipeline.builder().contextK(0));
        assertThrows(IllegalArgumentException.class, () -> RagPipeline.builder()
                .retriever(new StubRetriever()).generator(new StubGenerator()).candidateK(2).contextK(3).build());
        assertThrows(NullPointerException.class, () -> RagPipeline.builder().generator(new StubGenerator()).build());
    }
}

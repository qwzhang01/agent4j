package io.github.qwzhang01.agent.rag.pipeline;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
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
import io.github.qwzhang01.agent.rag.model.SearchFilter;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import io.github.qwzhang01.agent.rag.generate.LlmAnswerGenerator;
import io.github.qwzhang01.agent.rag.rerank.FallbackReranker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

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

    private static class StubRetriever implements Retriever {
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
    void searchFilterReachesTheRetriever() {
        AtomicReference<SearchFilter> seen = new AtomicReference<>();
        Retriever retriever = new StubRetriever() {
            @Override
            public Result retrieveWithStages(String query, int topK, SearchFilter filter) {
                seen.set(filter);
                return new Result(result, Map.of(), List.of());
            }
        };
        SearchFilter access = SearchFilter.of(Map.of("v", "2")).withDocIdPrefixes(List.of("a.md"));

        RagPipeline.builder().retriever(retriever).generator(new StubGenerator()).build()
                .ask(RagQuery.of("q", access));

        assertEquals(access, seen.get());
    }

    @Test
    void retrieverWithoutPrefixSupportFailsTheCallInsteadOfIgnoringTheRestriction() {
        StubRetriever retriever = new StubRetriever();
        StubGenerator generator = new StubGenerator();
        RagPipeline pipeline = RagPipeline.builder().retriever(retriever).generator(generator).build();

        RagException e = assertThrows(RagException.class,
                () -> pipeline.ask(RagQuery.of("q").withDocIdPrefixes(List.of("a.md"))));

        assertTrue(e.getCause() instanceof UnsupportedOperationException, e.toString());
        assertEquals(null, generator.lastContexts);
        assertEquals(null, retriever.lastQuery);
        assertEquals("a.md#0", pipeline.ask(new RagQuery("q", List.of(), Map.of("v", "2")))
                .contexts().get(0).chunk().chunkId());
    }

    @Test
    void emptyAllowedSetNeverCallsALegacyRetriever() {
        StubRetriever retriever = new StubRetriever();
        List<List<ScoredChunk>> contexts = new ArrayList<>();
        AnswerGenerator generator = (q, h, c) -> {
            contexts.add(c);
            return new GeneratedAnswer("no material", List.of(), true, 0, 0);
        };

        RagAnswer answer = RagPipeline.builder().retriever(retriever).generator(generator).build()
                .ask(RagQuery.of("q").withDocIdPrefixes(List.of()));

        assertEquals(null, retriever.lastQuery);
        assertEquals(List.of(List.of()), contexts);
        assertTrue(answer.refused());
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

    private static final class ScriptedModel implements ModelClient {
        final List<ModelRequest> requests = new ArrayList<>();
        final String reply;

        ScriptedModel(String reply) {
            this.reply = reply;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            return new ModelResponse(reply, null, "stop", null);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final Reranker FAILING = (q, c, n) -> {
        throw new RerankException("HTTP 503");
    };

    @Test
    void fallbackRerankerDegradationIsTraced() {
        StubGenerator generator = new StubGenerator();
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever())
                .reranker(new FallbackReranker(FAILING)).generator(generator).contextK(2).build()
                .ask(RagQuery.of("q"));

        assertEquals(List.of(ScoredChunk.RERANK), answer.trace().degradations());
        assertFalse(answer.trace().stageHits().containsKey(ScoredChunk.RERANK));
        assertEquals(List.of("a.md#0", "a.md#1"),
                generator.lastContexts.stream().map(c -> c.chunk().chunkId()).toList());
    }

    @Test
    void rerankThresholdRemovingEverythingRefusesWithoutModelCall() {
        Reranker lowScores = (q, c, n) -> c.stream().map(x -> x.withStage(ScoredChunk.RERANK, 0.01)).toList();
        ScriptedModel model = new ScriptedModel("unused");
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever())
                .reranker(new FallbackReranker(lowScores, 0.5)).generator(new LlmAnswerGenerator(model)).build()
                .ask(RagQuery.of("q"));

        assertTrue(answer.refused());
        assertTrue(answer.trace().refused());
        assertTrue(answer.contexts().isEmpty());
        assertTrue(answer.trace().contextChunkIds().isEmpty());
        assertEquals(List.of(), answer.trace().stageHits().get(ScoredChunk.RERANK));
        assertTrue(answer.trace().degradations().isEmpty());
        assertTrue(model.requests.isEmpty());
    }

    @Test
    void rerankerReturningNullFallsBackToFirstStage() {
        StubGenerator generator = new StubGenerator();
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever())
                .reranker((q, c, n) -> null).generator(generator).contextK(2).build()
                .ask(RagQuery.of("q"));

        assertEquals(2, generator.lastContexts.size());
        assertFalse(answer.refused());
        assertEquals(List.of(ScoredChunk.RERANK), answer.trace().degradations());
    }

    @Test
    void rerankerSeesAtMostCandidateK() {
        StubRetriever retriever = new StubRetriever();
        retriever.result = hits("a.md#0", "a.md#1", "a.md#2", "a.md#3");
        List<Integer> seen = new ArrayList<>();
        Reranker recording = (q, c, n) -> {
            seen.add(c.size());
            return c.subList(0, n);
        };
        RagPipeline.builder().retriever(retriever).reranker(recording).generator(new StubGenerator())
                .candidateK(3).contextK(2).build().ask(RagQuery.of("q"));
        assertEquals(List.of(3), seen);
    }

    @Test
    void contextsAreTheChunksTheModelSaw() {
        AnswerGenerator partial = (q, h, contexts) -> new GeneratedAnswer("answer [a.md#0]",
                List.of(new AnswerSentence("answer", List.of("a.md#0"), null, null)), false, 1, 1,
                List.of("a.md#0"));
        List<Map<String, Chunk>> verified = new ArrayList<>();
        CitationVerifier verifier = (s, byId) -> {
            verified.add(byId);
            return new CitationVerifier.Verification(s, 0, 0, false);
        };
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever()).generator(partial)
                .verifier(verifier).contextK(3).build().ask(RagQuery.of("q"));

        assertEquals(List.of("a.md#0"), answer.contexts().stream().map(c -> c.chunk().chunkId()).toList());
        assertEquals(List.of("a.md#0"), answer.trace().contextChunkIds());
        assertEquals(List.of("a.md#0"), List.copyOf(verified.get(0).keySet()));
    }

    @Test
    void llmGeneratorBudgetDropsUnseenChunksFromContexts() {
        StubRetriever retriever = new StubRetriever();
        ScriptedModel model = new ScriptedModel("答[a.md#0]。");
        RagAnswer answer = RagPipeline.builder().retriever(retriever)
                .generator(new LlmAnswerGenerator(model, new LlmAnswerGenerator.Options(null, 60, null, 6)))
                .contextK(3).build().ask(RagQuery.of("q"));

        assertEquals(List.of("a.md#0"), answer.trace().contextChunkIds());
        assertEquals(List.of("a.md#0"), answer.sentences().get(0).citedChunkIds());
    }

    @Test
    void generatorSeesHistoryAndOriginalQuestion() {
        ScriptedModel model = new ScriptedModel("答[a.md#0]。");
        QueryRewriter rewriter = (q, h) -> new QueryRewriter.Rewrite("RAG 引用溯源怎么做", 0, 0, false);
        RagPipeline.builder().queryRewriter(rewriter).retriever(new StubRetriever())
                .generator(new LlmAnswerGenerator(model)).build()
                .ask(new RagQuery("那引用溯源呢", List.of(ConversationTurn.user("介绍 RAG"),
                        ConversationTurn.assistant("RAG 是检索增强生成。")), Map.of()));

        List<ChatMessage> messages = model.requests.get(0).messages();
        assertEquals("介绍 RAG", messages.get(1).content());
        assertEquals(ChatRole.ASSISTANT, messages.get(2).role());
        assertTrue(messages.get(3).content().endsWith("那引用溯源呢"));
    }

    @Test
    void refusedAnswerSkipsVerifier() {
        CitationVerifier verifier = (s, byId) -> {
            throw new AssertionError("must not be called");
        };
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever())
                .generator(new LlmAnswerGenerator(new ScriptedModel("资料中没有相关内容。")))
                .verifier(verifier).build().ask(RagQuery.of("q"));
        assertTrue(answer.trace().refused());
        assertTrue(answer.sentences().isEmpty());
        assertTrue(answer.trace().verdictCounts().isEmpty());
    }

    @Test
    void verifierReturningWrongSentenceCountDegrades() {
        CitationVerifier dropsAll = (s, byId) -> new CitationVerifier.Verification(List.of(), 4, 2, false);
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever()).generator(new StubGenerator())
                .verifier(dropsAll).build().ask(RagQuery.of("q"));
        assertEquals(1, answer.sentences().size());
        assertEquals(SupportVerdict.UNVERIFIED, answer.sentences().get(0).verdict());
        assertEquals(List.of("verify"), answer.trace().degradations());
        assertEquals(14, answer.trace().promptTokens());
    }

    @Test
    void blankRewriteRetrievesWithOriginalQuestion() {
        StubRetriever retriever = new StubRetriever();
        QueryRewriter blank = (q, h) -> new QueryRewriter.Rewrite(" ", 3, 1, false);
        RagAnswer answer = RagPipeline.builder().queryRewriter(blank).retriever(retriever)
                .generator(new StubGenerator()).build()
                .ask(new RagQuery("原问题", List.of(ConversationTurn.user("h")), Map.of()));
        assertEquals("原问题", retriever.lastQuery);
        assertEquals(List.of("rewrite"), answer.trace().degradations());
        assertEquals(13, answer.trace().promptTokens());
    }

    @Test
    void emptyModelOutputFallsBackToPassages() {
        RagAnswer answer = RagPipeline.builder().retriever(new StubRetriever())
                .generator(new LlmAnswerGenerator(new ScriptedModel("  "))).contextK(2).build()
                .ask(RagQuery.of("q"));
        assertEquals(List.of("generate"), answer.trace().degradations());
        assertTrue(answer.answer().contains("[a.md#0]"));
        assertEquals(List.of("a.md#0", "a.md#1"), answer.trace().contextChunkIds());
    }

    @Test
    void fallbackTextWithoutContexts() {
        assertTrue(PassageListFallback.render(List.of()).contains("没有检索到"));
    }

    @Test
    void builderValidation() {
        assertThrows(IllegalArgumentException.class, () -> RagPipeline.builder().contextK(0));
        assertThrows(IllegalArgumentException.class, () -> RagPipeline.builder()
                .retriever(new StubRetriever()).generator(new StubGenerator()).candidateK(2).contextK(3).build());
        assertThrows(NullPointerException.class, () -> RagPipeline.builder().generator(new StubGenerator()).build());
    }
}

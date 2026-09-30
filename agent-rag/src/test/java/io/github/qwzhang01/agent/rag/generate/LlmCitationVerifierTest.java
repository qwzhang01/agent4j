package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.rag.CitationVerifier.Verification;
import io.github.qwzhang01.agent.rag.model.AnswerSentence;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.SupportVerdict;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCitationVerifierTest {

    private static final Map<String, Chunk> CHUNKS = Map.of(
            "a.md#1", chunk("a.md#1", "召回使用 BM25 与向量两路"),
            "b.md#2", chunk("b.md#2", "RRF 的 k 取 60"),
            "c.md#3", chunk("c.md#3", "重排使用 cross-encoder"));

    private static Chunk chunk(String id, String text) {
        return new Chunk(id, id.substring(0, id.indexOf('#')), null, null, null, text, 0, 0, null, null);
    }

    private static AnswerSentence s(String text, String... ids) {
        return new AnswerSentence(text, List.of(ids), null, null);
    }

    private static ModelResponse reply(String content) {
        return new ModelResponse(content, null, "stop", new ModelResponse.TokenUsage(10, 3, 13));
    }

    @Test
    void sentencesWithoutCitationsSkipModel() {
        Verification v = new LlmCitationVerifier(StubModelClient.failing())
                .verify(List.of(s("没有引用"), s("幽灵引用", "ghost.md#1")), CHUNKS);
        assertEquals(SupportVerdict.NOT_FOUND, v.sentences().get(0).verdict());
        assertEquals("no citation", v.sentences().get(0).reason());
        assertEquals(SupportVerdict.NOT_FOUND, v.sentences().get(1).verdict());
        assertFalse(v.degraded());
        assertEquals(0, v.promptTokens());
    }

    @Test
    void parsesFencedJsonAndSendsOnlyCitedEvidence() {
        StubModelClient client = new StubModelClient(r -> reply("""
                ```json
                {"results":[{"i":0,"verdict":"SUPPORTED","reason":"原文明确"},{"i":1,"verdict":"contradicted","reason":"k 是 60"}]}
                ```"""));
        Verification v = new LlmCitationVerifier(client).verify(List.of(
                s("两路召回", "a.md#1"), s("没有引用"), s("k 取 10", "b.md#2")), CHUNKS);
        assertEquals(1, client.requests.size());
        String prompt = client.lastUserPrompt();
        assertTrue(prompt.contains("召回使用 BM25"));
        assertTrue(prompt.contains("RRF 的 k 取 60"));
        assertFalse(prompt.contains("cross-encoder"));
        assertNotNull(client.requests.get(0).responseFormat());

        assertEquals(List.of(SupportVerdict.SUPPORTED, SupportVerdict.NOT_FOUND, SupportVerdict.CONTRADICTED),
                v.sentences().stream().map(AnswerSentence::verdict).toList());
        assertEquals("两路召回", v.sentences().get(0).text());
        assertEquals("k 是 60", v.sentences().get(2).reason());
        assertEquals(10, v.promptTokens());
        assertEquals(3, v.completionTokens());
        assertFalse(v.degraded());
    }

    @Test
    void toleratesTextAroundJson() {
        StubModelClient client = new StubModelClient(r -> reply(
                "结果如下：{\"results\":[{\"i\":0,\"verdict\":\"NOT_FOUND\",\"reason\":\"未提及\"}]} 以上。"));
        Verification v = new LlmCitationVerifier(client).verify(List.of(s("x", "c.md#3")), CHUNKS);
        assertEquals(SupportVerdict.NOT_FOUND, v.sentences().get(0).verdict());
        assertFalse(v.degraded());
    }

    @Test
    void batchFailureIsIsolatedAndOrderPreserved() {
        AtomicInteger calls = new AtomicInteger();
        StubModelClient client = new StubModelClient(r -> {
            int n = calls.getAndIncrement();
            if (n == 1) {
                throw new IllegalStateException("timeout");
            }
            return reply("{\"results\":[{\"i\":0,\"verdict\":\"SUPPORTED\"},{\"i\":1,\"verdict\":\"SUPPORTED\"}]}");
        });
        List<AnswerSentence> input = List.of(
                s("s0", "a.md#1"), s("s1", "b.md#2"),
                s("s2", "c.md#3"), s("s3", "a.md#1"),
                s("s4", "b.md#2"));
        Verification v = new LlmCitationVerifier(client, new LlmCitationVerifier.Options(null, 4000, 2))
                .verify(input, CHUNKS);
        assertEquals(3, client.requests.size());
        assertEquals(List.of("s0", "s1", "s2", "s3", "s4"), v.sentences().stream().map(AnswerSentence::text).toList());
        assertEquals(List.of(SupportVerdict.SUPPORTED, SupportVerdict.SUPPORTED,
                        SupportVerdict.UNVERIFIED, SupportVerdict.UNVERIFIED, SupportVerdict.SUPPORTED),
                v.sentences().stream().map(AnswerSentence::verdict).toList());
        assertTrue(v.degraded());
        assertEquals(20, v.promptTokens());
    }

    @Test
    void garbageOutputAndMissingEntriesDegrade() {
        Verification garbage = new LlmCitationVerifier(new StubModelClient(r -> reply("I cannot comply")))
                .verify(List.of(s("x", "a.md#1")), CHUNKS);
        assertEquals(SupportVerdict.UNVERIFIED, garbage.sentences().get(0).verdict());
        assertTrue(garbage.degraded());

        Verification partial = new LlmCitationVerifier(new StubModelClient(r -> reply(
                "{\"results\":[{\"i\":1,\"verdict\":\"SUPPORTED\"},{\"i\":0,\"verdict\":\"MAYBE\"}]}")))
                .verify(List.of(s("x", "a.md#1"), s("y", "b.md#2")), CHUNKS);
        assertEquals(SupportVerdict.UNVERIFIED, partial.sentences().get(0).verdict());
        assertEquals(SupportVerdict.SUPPORTED, partial.sentences().get(1).verdict());
        assertTrue(partial.degraded());
    }

    @Test
    void oneBasedNumberingIsNotShiftedOntoTheWrongSentence() {
        Verification v = new LlmCitationVerifier(new StubModelClient(r -> reply(
                "{\"results\":[{\"i\":1,\"verdict\":\"CONTRADICTED\"},{\"i\":2,\"verdict\":\"SUPPORTED\"}]}")))
                .verify(List.of(s("x", "a.md#1"), s("y", "b.md#2")), CHUNKS);
        assertEquals(List.of(SupportVerdict.CONTRADICTED, SupportVerdict.SUPPORTED),
                v.sentences().stream().map(AnswerSentence::verdict).toList());
        assertFalse(v.degraded());
    }

    @Test
    void extraAndOutOfRangeEntriesAreIgnored() {
        Verification v = new LlmCitationVerifier(new StubModelClient(r -> reply(
                "{\"results\":[{\"i\":0,\"verdict\":\"SUPPORTED\"},{\"i\":7,\"verdict\":\"CONTRADICTED\"},"
                        + "{\"i\":-1,\"verdict\":\"CONTRADICTED\"},\"junk\"]}")))
                .verify(List.of(s("x", "a.md#1")), CHUNKS);
        assertEquals(SupportVerdict.SUPPORTED, v.sentences().get(0).verdict());
        assertFalse(v.degraded());
    }

    @Test
    void lenientIndexAndVerdictSpelling() {
        Verification v = new LlmCitationVerifier(new StubModelClient(r -> reply(
                "以下是结果 [{\"i\":\"0\",\"verdict\":\"not found\"},{\"i\":1,\"verdict\":\"Not-Found\"}] 完毕")))
                .verify(List.of(s("x", "a.md#1"), s("y", "b.md#2")), CHUNKS);
        assertEquals(List.of(SupportVerdict.NOT_FOUND, SupportVerdict.NOT_FOUND),
                v.sentences().stream().map(AnswerSentence::verdict).toList());
        assertFalse(v.degraded());
    }

    @Test
    void evidenceIsCappedPerSentence() {
        Map<String, Chunk> big = Map.of("a.md#1", chunk("a.md#1", "z".repeat(10_000)));
        StubModelClient client = new StubModelClient(r -> reply("{\"results\":[{\"i\":0,\"verdict\":\"SUPPORTED\"}]}"));
        new LlmCitationVerifier(client, new LlmCitationVerifier.Options(null, 500, 8))
                .verify(List.of(s("x", "a.md#1")), big);
        assertTrue(client.lastUserPrompt().length() < 700);
    }
}

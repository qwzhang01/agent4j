package io.github.qwzhang01.agent.rag.generate;

import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.rag.model.Chunk;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import io.github.qwzhang01.agent.rag.model.GeneratedAnswer;
import io.github.qwzhang01.agent.rag.model.ScoredChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmAnswerGeneratorTest {

    static ScoredChunk chunk(String id, String text, double score) {
        String docId = id.substring(0, id.indexOf('#'));
        return ScoredChunk.of(new Chunk(id, docId, "标题-" + docId, null, List.of("第一章", "召回"), text,
                1, 2, null, null), ScoredChunk.RRF, score);
    }

    @Test
    void emptyContextsRefuseWithoutModelCall() {
        LlmAnswerGenerator gen = new LlmAnswerGenerator(StubModelClient.failing());
        GeneratedAnswer a = gen.generate("问题", List.of(), List.of());
        assertTrue(a.refused());
        assertEquals("资料中没有相关内容", a.rawText());
        assertTrue(a.sentences().isEmpty());
        assertEquals(0, a.promptTokens());
    }

    @Test
    void promptContainsIdsTitlesSectionsAndRules() {
        StubModelClient client = StubModelClient.replying("两路召回[a.md#1]。", 100, 20);
        new LlmAnswerGenerator(client).generate("怎么召回？", List.of(),
                List.of(chunk("a.md#1", "BM25 与向量两路召回", 0.9), chunk("b.md#4", "RRF 融合", 0.5)));
        ModelRequest req = client.requests.get(0);
        assertEquals(ChatRole.SYSTEM, req.messages().get(0).role());
        String system = req.messages().get(0).content();
        assertTrue(system.contains("资料中没有相关内容"));
        assertTrue(system.contains("[chunkId]"));
        String user = client.lastUserPrompt();
        assertTrue(user.contains("[a.md#1] 《标题-a.md》 section: 第一章 > 召回\nBM25 与向量两路召回"));
        assertTrue(user.contains("[b.md#4]"));
        assertTrue(user.indexOf("[a.md#1]") < user.indexOf("[b.md#4]"));
        assertTrue(user.endsWith("怎么召回？"));
    }

    @Test
    void parsesSentencesAndUsage() {
        StubModelClient client = StubModelClient.replying("两路召回[a.md#1]。再融合[b.md#4][z.md#1]。", 100, 20);
        GeneratedAnswer a = new LlmAnswerGenerator(client).generate("q", null,
                List.of(chunk("a.md#1", "x", 1), chunk("b.md#4", "y", 0.5)));
        assertFalse(a.refused());
        assertEquals(2, a.sentences().size());
        assertEquals(List.of("b.md#4"), a.sentences().get(1).citedChunkIds());
        assertEquals(100, a.promptTokens());
        assertEquals(20, a.completionTokens());
    }

    @Test
    void refusalDetectedAfterTrim() {
        StubModelClient client = StubModelClient.replying("  资料中没有相关内容。\n", 50, 5);
        GeneratedAnswer a = new LlmAnswerGenerator(client).generate("q", List.of(), List.of(chunk("a.md#1", "x", 1)));
        assertTrue(a.refused());
        assertTrue(a.sentences().isEmpty());
        assertEquals(50, a.promptTokens());
    }

    @Test
    void refusalWithPreambleIsDetected() {
        StubModelClient client = StubModelClient.replying("抱歉，资料中没有相关内容。", 5, 5);
        GeneratedAnswer a = new LlmAnswerGenerator(client).generate("q", List.of(), List.of(chunk("a.md#1", "x", 1)));
        assertTrue(a.refused());
        assertTrue(a.sentences().isEmpty());
    }

    @Test
    void citedAnswerMentioningRefusalTextIsNotARefusal() {
        StubModelClient client = StubModelClient.replying("A 部分资料中没有相关内容，但 B 是 C[a.md#1]。", 5, 5);
        GeneratedAnswer a = new LlmAnswerGenerator(client).generate("q", List.of(), List.of(chunk("a.md#1", "x", 1)));
        assertFalse(a.refused());
        assertEquals(List.of("a.md#1"), a.sentences().get(0).citedChunkIds());
    }

    @Test
    void promptForbidsTranslatingTheRefusal() {
        StubModelClient client = StubModelClient.replying("ok[a.md#1].", 1, 1);
        new LlmAnswerGenerator(client).generate("What is RAG?", List.of(), List.of(chunk("a.md#1", "x", 1)));
        String system = client.requests.get(0).messages().get(0).content();
        assertTrue(system.contains("不要翻译它"));
    }

    @Test
    void reportsPackedChunkIdsAndStripsHallucinatedMarkers() {
        String body = "x".repeat(300);
        StubModelClient client = StubModelClient.replying("答[a.md#1][c.md#3]。", 1, 1);
        GeneratedAnswer a = new LlmAnswerGenerator(client, new LlmAnswerGenerator.Options(null, 700, null, 6))
                .generate("q", List.of(), List.of(chunk("a.md#1", body, 0.9), chunk("b.md#2", body, 0.8),
                        chunk("c.md#3", body, 0.7)));
        assertEquals(List.of("a.md#1", "b.md#2"), a.usedChunkIds());
        assertEquals("答[a.md#1]。", a.rawText());
    }

    @Test
    void emptyContextsReportNoUsedChunks() {
        GeneratedAnswer a = new LlmAnswerGenerator(StubModelClient.failing()).generate("q", List.of(), List.of());
        assertEquals(List.of(), a.usedChunkIds());
    }

    @Test
    void blankModelOutputIsAFailure() {
        StubModelClient client = StubModelClient.replying("  \n", 1, 1);
        LlmAnswerGenerator gen = new LlmAnswerGenerator(client);
        assertThrows(IllegalStateException.class,
                () -> gen.generate("q", List.of(), List.of(chunk("a.md#1", "x", 1))));
    }

    @Test
    void oversizedTopChunkTruncationKeepsSurrogatePairs() {
        String header = LlmAnswerGenerator.header(chunk("a.md#1", "", 1).chunk());
        int room = 200 - header.length() - 3;
        String text = "y".repeat(room - 1) + "😀" + "z".repeat(100);
        List<Chunk> packed = LlmAnswerGenerator.pack(List.of(chunk("a.md#1", text, 1)), 200);
        assertEquals("y".repeat(room - 1), packed.get(0).text());
    }

    @Test
    void customRefusalText() {
        StubModelClient client = StubModelClient.replying("NO_ANSWER", 1, 1);
        LlmAnswerGenerator gen = new LlmAnswerGenerator(client,
                new LlmAnswerGenerator.Options("m1", 12_000, "NO_ANSWER", 6));
        assertTrue(gen.generate("q", List.of(), List.of(chunk("a.md#1", "x", 1))).refused());
        assertEquals("m1", client.requests.get(0).model());
    }

    @Test
    void truncationDropsLowestRankedWholeChunks() {
        String body = "x".repeat(300);
        List<ScoredChunk> ctx = List.of(chunk("a.md#1", body, 0.9), chunk("b.md#2", body, 0.8), chunk("c.md#3", body, 0.7));
        List<Chunk> packed = LlmAnswerGenerator.pack(ctx, 700);
        assertEquals(List.of("a.md#1", "b.md#2"), packed.stream().map(Chunk::chunkId).toList());

        StubModelClient client = StubModelClient.replying("答[c.md#3]。", 1, 1);
        GeneratedAnswer a = new LlmAnswerGenerator(client, new LlmAnswerGenerator.Options(null, 700, null, 6))
                .generate("q", List.of(), ctx);
        assertFalse(client.lastUserPrompt().contains("c.md#3"));
        assertTrue(a.sentences().get(0).citedChunkIds().isEmpty());
    }

    @Test
    void oversizedTopChunkIsTruncatedNotDropped() {
        List<Chunk> packed = LlmAnswerGenerator.pack(List.of(chunk("a.md#1", "y".repeat(5000), 1)), 200);
        assertEquals(1, packed.size());
        assertTrue(packed.get(0).text().length() < 200);
    }

    @Test
    void historyLimitedToMostRecentTurns() {
        StubModelClient client = StubModelClient.replying("答[a.md#1]。", 1, 1);
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("u1"), ConversationTurn.assistant("a1"),
                ConversationTurn.user("u2"), ConversationTurn.assistant("a2"));
        new LlmAnswerGenerator(client, new LlmAnswerGenerator.Options(null, 12_000, null, 2))
                .generate("q", history, List.of(chunk("a.md#1", "x", 1)));
        ModelRequest req = client.requests.get(0);
        assertEquals(4, req.messages().size());
        assertEquals("u2", req.messages().get(1).content());
        assertEquals(ChatRole.ASSISTANT, req.messages().get(2).role());
    }

    @Test
    void modelExceptionPropagates() {
        StubModelClient client = new StubModelClient(r -> {
            throw new IllegalStateException("boom");
        });
        LlmAnswerGenerator gen = new LlmAnswerGenerator(client);
        assertThrows(IllegalStateException.class,
                () -> gen.generate("q", List.of(), List.of(chunk("a.md#1", "x", 1))));
    }
}

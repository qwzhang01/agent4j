package io.github.qwzhang01.agent.rag.query;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.rag.QueryRewriter.Rewrite;
import io.github.qwzhang01.agent.rag.model.ConversationTurn;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LlmQueryRewriterTest {

    private static final class ScriptedClient implements ModelClient {
        final Deque<Supplier<ModelResponse>> script = new ArrayDeque<>();
        final List<ModelRequest> requests = new ArrayList<>();

        ScriptedClient reply(String text, int prompt, int completion) {
            script.add(() -> new ModelResponse(text, null, "stop",
                    new ModelResponse.TokenUsage(prompt, completion, prompt + completion)));
            return this;
        }

        ScriptedClient replyText(String text) {
            script.add(() -> ModelResponse.text(text));
            return this;
        }

        ScriptedClient fail() {
            script.add(() -> {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR, "provider down");
            });
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            return script.poll().get();
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            throw new UnsupportedOperationException();
        }
    }

    private static final List<ConversationTurn> HISTORY = List.of(
            ConversationTurn.user("RAG 怎么做混合检索？"),
            ConversationTurn.assistant("用 BM25 和向量检索并行召回，再用 RRF 融合。"));

    @Test
    void noHistorySkipsModel() {
        ScriptedClient client = new ScriptedClient();
        LlmQueryRewriter rewriter = new LlmQueryRewriter(client);
        assertEquals(Rewrite.unchanged("什么是 RAG"), rewriter.rewrite("什么是 RAG", List.of()));
        assertEquals(Rewrite.unchanged("什么是 RAG"), rewriter.rewrite("什么是 RAG", null));
        assertTrue(client.requests.isEmpty());
    }

    @Test
    void rewritesAndReportsUsage() {
        ScriptedClient client = new ScriptedClient().reply("RAG 中引用溯源的实现方案", 120, 12);
        Rewrite r = new LlmQueryRewriter(client, "doubao-lite", 6, 500).rewrite("那引用溯源怎么做", HISTORY);

        assertEquals(new Rewrite("RAG 中引用溯源的实现方案", 120, 12, false), r);
        ModelRequest req = client.requests.get(0);
        assertEquals("doubao-lite", req.model());
        assertEquals(0.0, req.temperature());
        assertEquals(2, req.messages().size());
    }

    @Test
    void missingUsageReportsZeroTokens() {
        ScriptedClient client = new ScriptedClient().replyText("RAG citation tracing");
        Rewrite r = new LlmQueryRewriter(client).rewrite("and citations?", HISTORY);
        assertEquals(new Rewrite("RAG citation tracing", 0, 0, false), r);
        assertNull(client.requests.get(0).model());
    }

    @Test
    void cleansPrefixesQuotesAndExtraLines() {
        assertEquals("RAG 引用溯源", LlmQueryRewriter.clean("改写后：“RAG 引用溯源”"));
        assertEquals("RAG 引用溯源", LlmQueryRewriter.clean("\n  \"RAG 引用溯源\"\n解释：补全了主语"));
        assertEquals("hybrid search in RAG", LlmQueryRewriter.clean("Rewritten query: 'hybrid search in RAG'"));
        assertEquals("《红楼梦》作者", LlmQueryRewriter.clean("《红楼梦》作者"));
        assertEquals("", LlmQueryRewriter.clean("  \n "));
        assertEquals("", LlmQueryRewriter.clean(null));

        ScriptedClient client = new ScriptedClient().reply("查询：「RAG 引用溯源方案」", 10, 5);
        assertEquals("RAG 引用溯源方案", new LlmQueryRewriter(client).rewrite("那引用呢", HISTORY).query());
    }

    @Test
    void modelFailureDegrades() {
        Rewrite r = new LlmQueryRewriter(new ScriptedClient().fail()).rewrite("那引用呢", HISTORY);
        assertEquals(new Rewrite("那引用呢", 0, 0, true), r);
    }

    @Test
    void blankOrOverlongOutputDegradesButKeepsTokens() {
        Rewrite blank = new LlmQueryRewriter(new ScriptedClient().reply("  \"\" ", 50, 2)).rewrite("那引用呢", HISTORY);
        assertEquals(new Rewrite("那引用呢", 50, 2, true), blank);

        Rewrite longOut = new LlmQueryRewriter(new ScriptedClient().reply("很长".repeat(80), 50, 160))
                .rewrite("那引用呢", HISTORY);
        assertTrue(longOut.degraded());
        assertEquals("那引用呢", longOut.query());
        assertEquals(160, longOut.completionTokens());
    }

    @Test
    void promptIncludesRecentHistoryInOrderTruncated() {
        List<ConversationTurn> history = List.of(
                ConversationTurn.user("第一轮问题"),
                ConversationTurn.assistant("第一轮回答"),
                ConversationTurn.user("第二轮问题"),
                ConversationTurn.assistant("第二轮回答" + "x".repeat(50)));
        ScriptedClient client = new ScriptedClient().replyText("ok");
        new LlmQueryRewriter(client, null, 3, 10).rewrite("那第三个呢", history);

        ChatMessage system = client.requests.get(0).messages().get(0);
        String user = client.requests.get(0).messages().get(1).content();
        assertTrue(system.content().contains("独立"));
        assertFalse(user.contains("第一轮问题"));
        int a = user.indexOf("第一轮回答");
        int b = user.indexOf("第二轮问题");
        int c = user.indexOf("第二轮回答");
        int q = user.indexOf("那第三个呢");
        assertTrue(a >= 0 && a < b && b < c && c < q, user);
        assertTrue(user.contains("助手: 第二轮回答xxxxx…"), user);
        assertTrue(user.contains("用户: 第二轮问题"), user);
    }

    @Test
    void identityRewriterReturnsQuestion() {
        assertEquals(Rewrite.unchanged("q"), IdentityQueryRewriter.INSTANCE.rewrite("q", HISTORY));
    }
}

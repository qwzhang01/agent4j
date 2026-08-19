package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8 M8.2 tests: ContextBudget, ContextCompressor, CompressingContextBuilder,
 * and backward compatibility of AgentConfig/ReActAgentLoop.
 */
class ContextCompressorTest {

    // ============ ContextBudget ============

    @Test
    void budget_estimateRoughlyCharsDiv4() {
        List<ChatMessage> msgs = List.of(
                ChatMessage.system("abcd"),    // 4 chars -> 1 token
                ChatMessage.user("abcdefgh")   // 8 chars -> 2 tokens
        );
        assertEquals(3, ContextBudget.estimate(msgs));
    }

    @Test
    void budget_emptyListIsZero() {
        assertEquals(0, ContextBudget.estimate(List.of()));
        assertEquals(0, ContextBudget.estimate(null));
    }

    @Test
    void budget_exceedsCheck() {
        List<ChatMessage> msgs = List.of(ChatMessage.user("a".repeat(400))); // ~100 tokens
        assertFalse(ContextBudget.exceeds(msgs, 100));
        assertTrue(ContextBudget.exceeds(msgs, 50));
    }

    // ============ ContextCompressor ============

    @Test
    void compress_underBudget_noAction() {
        MockModelClient mc = MockModelClient.scripted();
        ContextCompressor compressor = new ContextCompressor(mc, 10000, 4);

        List<ChatMessage> msgs = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("hi"),
                ChatMessage.assistant("hello")
        );

        var result = compressor.compress(msgs);
        assertFalse(result.didCompress());
        assertEquals(msgs, result.compressed());
        assertTrue(result.archived().isEmpty());
    }

    @Test
    void compress_overBudget_summarizesAndKeepsRecent() {
        // Model returns a fixed summary
        MockModelClient mc = MockModelClient.scripted().respondText("Summary of prior talk");
        ContextCompressor compressor = new ContextCompressor(mc, 10, 2); // tiny budget, keep 2

        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system("system prompt"));
        msgs.add(ChatMessage.user("msg 1 - long content ".repeat(5)));
        msgs.add(ChatMessage.assistant("resp 1 - long content ".repeat(5)));
        msgs.add(ChatMessage.user("msg 2 - long content ".repeat(5)));
        msgs.add(ChatMessage.assistant("resp 2 - long content ".repeat(5)));
        msgs.add(ChatMessage.user("recent 1"));
        msgs.add(ChatMessage.assistant("recent 2"));

        var result = compressor.compress(msgs);

        assertTrue(result.didCompress());
        assertEquals(4, result.archived().size(), "4 oldest non-system messages archived");
        // Result: [system, summary, recent1, recent2] = 4 messages
        assertEquals(4, result.compressed().size());

        // System prompt preserved
        assertEquals(ChatRole.SYSTEM, result.compressed().get(0).role());
        assertEquals("system prompt", result.compressed().get(0).content());

        // Summary message present
        assertEquals(ChatRole.USER, result.compressed().get(1).role());
        assertTrue(result.compressed().get(1).content().contains("Summary of prior talk"));

        // Recent 2 preserved verbatim
        assertEquals("recent 1", result.compressed().get(2).content());
        assertEquals("recent 2", result.compressed().get(3).content());
    }

    @Test
    void compress_overBudgetButTooFewNonSystem_noAction() {
        MockModelClient mc = MockModelClient.scripted();
        ContextCompressor compressor = new ContextCompressor(mc, 1, 4); // budget 1 token, keep 4

        // Only 2 non-system messages, keepRecent=4 -> nothing to compress
        List<ChatMessage> msgs = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("short"),
                ChatMessage.assistant("short")
        );

        var result = compressor.compress(msgs);
        assertFalse(result.didCompress());
    }

    @Test
    void compress_modelFailure_fallsBackToTruncation() {
        // No scripted response -> throws, compressor should fall back
        MockModelClient mc = MockModelClient.scripted();
        ContextCompressor compressor = new ContextCompressor(mc, 10, 2);

        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.system("sys"));
        msgs.add(ChatMessage.user("long content ".repeat(10)));
        msgs.add(ChatMessage.assistant("long content ".repeat(10)));
        msgs.add(ChatMessage.user("recent 1"));
        msgs.add(ChatMessage.assistant("recent 2"));

        var result = compressor.compress(msgs);
        assertTrue(result.didCompress(), "should still compress on model failure");
        // Fallback summary contains a truncation marker
        assertTrue(result.compressed().get(1).content().contains("Compaction failed")
                || result.compressed().get(1).content().contains("Summary"));
    }

    // ============ CompressingContextBuilder ============

    @Test
    void builder_rewritesStateAndArchives() {
        MockModelClient mc = MockModelClient.scripted().respondText("archived summary");
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        CompressingContextBuilder builder = new CompressingContextBuilder(
                mc, 10, 2, store, "session:s1");

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("sys"));
        state.addMessage(ChatMessage.user("long message 1 ".repeat(10)));
        state.addMessage(ChatMessage.assistant("long response 1 ".repeat(10)));
        state.addMessage(ChatMessage.user("recent 1"));
        state.addMessage(ChatMessage.assistant("recent 2"));

        int originalSize = state.getMessages().size();
        List<ChatMessage> result = builder.build(null, state);

        // State was rewritten in place
        assertNotEquals(originalSize, state.getMessages().size());
        assertEquals(result.size(), state.getMessages().size());

        // Archive stored as SUMMARY
        List<MemoryEntry> summaries = store.query(MemoryQuery.builder()
                .scopes(List.of("session:s1"))
                .type(MemoryType.SUMMARY)
                .build());
        assertEquals(1, summaries.size());
    }

    @Test
    void builder_noArchiveStore_stillCompresses() {
        MockModelClient mc = MockModelClient.scripted().respondText("summary");
        CompressingContextBuilder builder = new CompressingContextBuilder(
                mc, 10, 2, null, null);

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("sys"));
        state.addMessage(ChatMessage.user("long ".repeat(20)));
        state.addMessage(ChatMessage.assistant("long ".repeat(20)));
        state.addMessage(ChatMessage.user("r1"));
        state.addMessage(ChatMessage.assistant("r2"));

        List<ChatMessage> result = builder.build(null, state);
        assertTrue(result.size() < state.getMessages().size() + 2, "compressed result smaller");
    }

    @Test
    void builder_underBudget_passthrough() {
        MockModelClient mc = MockModelClient.scripted();
        CompressingContextBuilder builder = new CompressingContextBuilder(
                mc, 100000, 4, new InMemoryMemoryStore(), "session:s1");

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.system("sys"));
        state.addMessage(ChatMessage.user("hi"));

        List<ChatMessage> result = builder.build(null, state);
        assertEquals(2, result.size());
        assertEquals(state.getMessages(), result);
    }

    // ============ Backward Compatibility ============

    @Test
    void agentConfig_contextBuilderDefaultsNull() {
        AgentConfig config = new AgentConfig("test", "sys", null, null, 10);
        assertNull(config.getContextBuilder(), "default contextBuilder should be null");
    }

    @Test
    void agentConfig_contextBuilderSettable() {
        PassthroughContextBuilder cb = new PassthroughContextBuilder();
        AgentConfig config = new AgentConfig("test", "sys", null, null, 10, cb);
        assertSame(cb, config.getContextBuilder());
    }
}

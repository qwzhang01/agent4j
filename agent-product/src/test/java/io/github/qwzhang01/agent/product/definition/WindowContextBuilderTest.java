package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M13.1 window builder tests: read-time trimming that never touches state.
 */
class WindowContextBuilderTest {

    private static ChatMessage msg(ChatRole role, String text) {
        return role == ChatRole.SYSTEM ? ChatMessage.system(text) : ChatMessage.user(text);
    }

    private AgentState stateWith(int count) {
        AgentState state = new AgentState();
        for (int i = 1; i <= count; i++) {
            state.addMessage(msg(ChatRole.USER, "m" + i));
        }
        return state;
    }

    @Test
    void shortHistoryPassesThroughUntouched() {
        AgentState state = stateWith(3);
        List<ChatMessage> window = new WindowContextBuilder(10).build(null, state);

        assertEquals(3, window.size());
        assertEquals("m1", window.get(0).content());
    }

    @Test
    void longHistoryKeepsSystemPlusRecentN() {
        AgentState state = new AgentState();
        state.addMessage(msg(ChatRole.SYSTEM, "persona"));
        for (int i = 1; i <= 10; i++) {
            state.addMessage(msg(ChatRole.USER, "m" + i));
        }

        List<ChatMessage> window = new WindowContextBuilder(4).build(null, state);

        assertEquals(5, window.size()); // system + 4 most recent
        assertEquals(ChatRole.SYSTEM, window.get(0).role());
        assertEquals("m7", window.get(1).content());
        assertEquals("m10", window.get(4).content());
    }

    @Test
    void longHistoryWithoutSystemKeepsRecentN() {
        AgentState state = stateWith(10);

        List<ChatMessage> window = new WindowContextBuilder(3).build(null, state);

        assertEquals(3, window.size());
        assertEquals("m8", window.get(0).content());
        assertEquals("m10", window.get(2).content());
    }

    @Test
    void trimmingIsReadTimeOnlyStateStaysComplete() {
        AgentState state = stateWith(10);
        new WindowContextBuilder(3).build(null, state);

        assertEquals(10, state.getMessages().size(),
                "windowing must NOT rewrite state (that is compaction's contract, Stage 8)");
    }

    @Test
    void exactlyAtWindowSizePassesThrough() {
        AgentState state = stateWith(4);
        List<ChatMessage> window = new WindowContextBuilder(4).build(null, state);
        assertEquals(4, window.size());
    }

    @Test
    void nonPositiveMaxMessagesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WindowContextBuilder(0));
        assertThrows(IllegalArgumentException.class, () -> new WindowContextBuilder(-1));
    }

    @Test
    void systemIsKeptEvenWhenItWouldFallInsideTheWindow() {
        // 6 messages: system + 5 user; window 10 -> everything fits (system not duplicated)
        AgentState state = new AgentState();
        state.addMessage(msg(ChatRole.SYSTEM, "persona"));
        for (int i = 1; i <= 5; i++) {
            state.addMessage(msg(ChatRole.USER, "m" + i));
        }
        List<ChatMessage> window = new WindowContextBuilder(10).build(null, state);

        assertEquals(6, window.size());
        long systemCount = window.stream().filter(m -> m.role() == ChatRole.SYSTEM).count();
        assertEquals(1, systemCount);
        assertTrue(window.stream().anyMatch(m -> "m5".equals(m.content())));
    }
}

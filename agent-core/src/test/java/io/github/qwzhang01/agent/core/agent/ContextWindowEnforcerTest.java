package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ToolCall;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextWindowEnforcer} (KP2).
 * <p>
 * Key scenarios:
 * <ol>
 *   <li>History within budget → returned unchanged.</li>
 *   <li>History over budget → oldest messages dropped.</li>
 *   <li>Tool-call pairs dropped together (assistant+tool_result invariant).</li>
 *   <li>Last message never dropped even if budget is 0.</li>
 *   <li>No delegate → raw state messages enforced.</li>
 *   <li>Delegate → delegate output is enforced.</li>
 * </ol>
 */
class ContextWindowEnforcerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============ Fast path: within budget ============

    @Test
    @DisplayName("history within budget is returned unchanged")
    void withinBudget_returnedUnchanged() {
        // budget: 10_000 total, 0 reserves → historyBudget = 10_000
        ContextWindowBudget budget = ContextWindowBudget.of(10_000, 0, 0, 0);
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(budget);

        AgentState state = stateWithMessages(
                ChatMessage.user("hello"),
                ChatMessage.assistant("world")
        );

        List<ChatMessage> result = enforcer.build(minimalConfig(), state);
        assertEquals(2, result.size(), "all messages returned when within budget");
    }

    // ============ Truncation: over budget ============

    @Test
    @DisplayName("oldest messages are dropped first when history exceeds budget")
    void overBudget_oldestDroppedFirst() {
        // 4 messages: "aaa..." 400 chars each → ~100 tokens each → total ~400 tokens
        // budget = 250 tokens → only last 2 messages can fit (~200 tokens)
        ContextWindowBudget budget = ContextWindowBudget.of(250, 0, 0, 0);
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(budget);

        String longContent = "a".repeat(400); // ~100 tokens each
        AgentState state = stateWithMessages(
                ChatMessage.user(longContent),        // oldest, dropped
                ChatMessage.assistant(longContent),   // dropped
                ChatMessage.user(longContent),        // kept
                ChatMessage.assistant(longContent)    // kept
        );

        List<ChatMessage> result = enforcer.build(minimalConfig(), state);
        assertTrue(result.size() < 4, "truncation should have occurred");
        // Last message must always be present
        assertEquals("assistant", result.get(result.size() - 1).role().name().toLowerCase());
    }

    @Test
    @DisplayName("last message is never dropped even when budget is zero")
    void lastMessageNeverDropped() {
        ContextWindowBudget budget = ContextWindowBudget.of(1, 0, 0, 0); // effectively 0 history
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(budget);

        AgentState state = stateWithMessages(
                ChatMessage.user("first"),
                ChatMessage.user("last")
        );

        List<ChatMessage> result = enforcer.build(minimalConfig(), state);
        assertFalse(result.isEmpty(), "result must never be empty");
        assertEquals("last", result.get(result.size() - 1).content(), "last message survives");
    }

    // ============ Tool-call pair invariant ============

    @Test
    @DisplayName("assistant-with-tool-calls and tool result are dropped as a pair")
    void toolCallPair_droppedTogether() {
        // Messages: [user] [assistant+toolCall] [tool result] [user] [assistant]
        // Budget forces dropping of the oldest 3 messages (the tool call pair)
        // The pair must be dropped together, not just the assistant message alone.
        ContextWindowBudget budget = ContextWindowBudget.of(200, 0, 0, 0);
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(budget);

        String longContent = "a".repeat(400); // ~100 tokens

        ToolCall toolCall = new ToolCall("call_1", "my_tool", mapper.createObjectNode());
        AgentState state = stateWithMessages(
                ChatMessage.user(longContent),                                     // oldest
                ChatMessage.assistantWithTools(null, List.of(toolCall)),          // tool-call pair start
                ChatMessage.tool("call_1", "my_tool", longContent),              // tool-call pair end
                ChatMessage.user(longContent),                                    // kept
                ChatMessage.assistant(longContent)                                // kept (last)
        );

        List<ChatMessage> result = enforcer.build(minimalConfig(), state);

        // The tool result (index 2) must never appear without its assistant (index 1)
        boolean hasToolResult = result.stream()
                .anyMatch(m -> m.role() == io.github.qwzhang01.agent.core.model.ChatRole.TOOL);
        boolean hasAssistantWithTools = result.stream()
                .anyMatch(m -> m.toolCalls() != null && !m.toolCalls().isEmpty());

        if (hasToolResult) {
            assertTrue(hasAssistantWithTools,
                    "tool result must always appear together with its assistant+toolCall message");
        }
    }

    // ============ Delegate wiring ============

    @Test
    @DisplayName("with delegate: delegate output is enforced, not raw state")
    void withDelegate_delegateOutputIsEnforced() {
        // Delegate returns only 1 short message regardless of state
        ContextBuilder delegate = (config, state) -> List.of(ChatMessage.user("short"));
        ContextWindowBudget budget = ContextWindowBudget.of(100, 0, 0, 0);
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(delegate, budget);

        // State has long messages that would overflow the budget if used directly
        AgentState state = stateWithMessages(
                ChatMessage.user("a".repeat(2000)),
                ChatMessage.user("b".repeat(2000))
        );

        List<ChatMessage> result = enforcer.build(minimalConfig(), state);
        assertEquals(1, result.size(), "only delegate output used");
        assertEquals("short", result.get(0).content());
    }

    // ============ Token estimation static helper ============

    @Test
    @DisplayName("estimateTokens: chars/4 heuristic")
    void estimateTokens_charsOver4() {
        List<ChatMessage> messages = List.of(ChatMessage.user("a".repeat(400))); // 400 chars
        int estimate = ContextWindowEnforcer.estimateTokens(messages);
        assertEquals(100, estimate, "400 chars / 4 = 100 tokens");
    }

    @Test
    @DisplayName("estimateTokens: null message list returns 0")
    void estimateTokens_null_returnsZero() {
        assertEquals(0, ContextWindowEnforcer.estimateTokens((List<ChatMessage>) null));
    }

    @Test
    @DisplayName("estimateTokens: null string returns 0")
    void estimateTokens_nullString_returnsZero() {
        assertEquals(0, ContextWindowEnforcer.estimateTokens((String) null));
    }

    // ============ Accessors ============

    @Test
    @DisplayName("getBudget returns the configured budget")
    void getBudget_returnsConfiguredBudget() {
        ContextWindowBudget budget = ContextWindowBudget.window32k();
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(budget);
        assertSame(budget, enforcer.getBudget());
    }

    @Test
    @DisplayName("getDelegate returns null when no delegate")
    void getDelegate_nullWhenNoDelegate() {
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(ContextWindowBudget.window8k());
        assertNull(enforcer.getDelegate());
    }

    // ============ Helpers ============

    private static AgentState stateWithMessages(ChatMessage... messages) {
        AgentState state = new AgentState();
        for (ChatMessage msg : messages) {
            state.addMessage(msg);
        }
        return state;
    }

    private static AgentConfig minimalConfig() {
        return new AgentConfig("test", "You are a test agent.", null, null, 10);
    }
}

package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decision 24 P2: HandoffInputFilter trims the next request, not AgentState.
 */
class HandoffInputFilterTest {

    @Test
    @DisplayName("keepWithin: B's request is trimmed; AgentState keeps the full ledger")
    void keepWithin_trimsRequest_notState() {
        var clientA = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"));

        ContextWindowBudget tiny = ContextWindowBudget.of(80, 0, 0, 0);
        AgentConfig b = new AgentConfig("B", "You are B.", clientB, null, 10);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, null, 10, null,
                List.of(HandoffSpec.to(b, HandoffInputFilter.keepWithin(tiny))));

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("a".repeat(400)));
        state.addMessage(ChatMessage.assistant("b".repeat(400)));
        new SimpleAgent(a).run("last-turn", state);

        List<ChatMessage> bHistory = clientB.requests.get(0).messages().stream()
                .filter(m -> m.role() != ChatRole.SYSTEM)
                .toList();
        assertTrue(ContextWindowEnforcer.estimateTokens(bHistory) <= tiny.historyBudget(),
                "B request history must fit the handoff budget");

        long userTurnsInState = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .count();
        assertTrue(userTurnsInState >= 2, "state must still hold the pre-handoff user turns");
        assertTrue(state.getMessages().size() > bHistory.size(),
                "ledger must be larger than the filtered request");
    }

    @Test
    @DisplayName("lastTurn: B sees from the last USER inclusive, including the handoff pair")
    void lastTurn_keepsFromLastUser() {
        var clientA = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"));

        AgentConfig b = new AgentConfig("B", "You are B.", clientB, null, 10);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, null, 10, null,
                List.of(HandoffSpec.to(b, HandoffInputFilter.lastTurn())));

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("old-1"));
        state.addMessage(ChatMessage.assistant("old-reply"));
        new SimpleAgent(a).run("handoff-now", state);

        List<ChatMessage> bHistory = clientB.requests.get(0).messages().stream()
                .filter(m -> m.role() != ChatRole.SYSTEM)
                .toList();
        assertEquals(ChatRole.USER, bHistory.get(0).role());
        assertEquals("handoff-now", bHistory.get(0).content());
        assertTrue(bHistory.stream().noneMatch(m -> "old-1".equals(m.content())),
                "older turns must not ride the wire");
        assertTrue(state.getMessages().stream().anyMatch(m -> "old-1".equals(m.content())),
                "older turns stay on the ledger");
    }

    @Test
    @DisplayName("IDENTITY (default): B sees the full history")
    void identity_carriesFullHistory() {
        var clientA = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new HandoffLoopTest.RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"));

        AgentConfig b = new AgentConfig("B", "You are B.", clientB, null, 10);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, null, 10, null,
                List.of(HandoffSpec.to(b)));

        AgentState state = new AgentState();
        state.addMessage(ChatMessage.user("old-1"));
        new SimpleAgent(a).run("now", state);

        List<String> bUsers = clientB.requests.get(0).messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .toList();
        assertTrue(bUsers.contains("old-1"));
        assertTrue(bUsers.contains("now"));
    }

    @Test
    @DisplayName("lastTurn with no USER keeps the last message")
    void lastTurn_noUser_keepsLast() {
        List<ChatMessage> history = List.of(ChatMessage.assistant("only"));
        List<ChatMessage> filtered = HandoffInputFilter.lastTurn().filter(history, null, null);
        assertEquals(1, filtered.size());
        assertEquals("only", filtered.get(0).content());
    }

    private static ToolCall handoffCall(String id, String targetName) {
        return ToolCall.of(id, "transfer_to_" + targetName, (com.fasterxml.jackson.databind.JsonNode) null);
    }
}

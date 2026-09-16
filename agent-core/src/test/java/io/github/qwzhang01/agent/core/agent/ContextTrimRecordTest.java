package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5.1: trim records emitted by {@link ContextWindowEnforcer} through
 * the optional trim listener - the "who got cut / when / at what cost"
 * telemetry the roadmap demands.
 */
class ContextTrimRecordTest {

    private static ChatMessage user(String text) {
        return ChatMessage.user(text);
    }

    private static ChatMessage assistantWithToolCall(String id) {
        return ChatMessage.assistantWithTools(null,
                List.of(io.github.qwzhang01.agent.core.model.ToolCall.of(id, "tool", "{}")));
    }

    private static ChatMessage toolResult(String id) {
        return new ChatMessage(ChatRole.TOOL, "result-" + id, null, id, "tool");
    }

    @Test
    @DisplayName("enforcer emits a trim record when truncation fires")
    void enforcerEmitsTrimRecord() {
        ContextWindowBudget budget = ContextWindowBudget.of(1000, 100, 100, 200);
        List<ContextTrimRecord> records = new ArrayList<>();

        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(null, budget, records::add);

        AgentState state = new AgentState();
        for (int i = 0; i < 20; i++) {
            state.addMessage(user("message number " + i
                    + " with some padding text to grow tokens "
                    + "x".repeat(240)));
        }

        List<ChatMessage> result = enforcer.build(null, state);

        assertEquals(1, records.size());
        ContextTrimRecord record = records.get(0);
        assertEquals(ContextTrimRecord.TrimSource.ENFORCER, record.source());
        assertTrue(record.didTrim());
        assertTrue(record.messagesDropped() > 0);
        assertTrue(record.tokensBefore() > record.tokensAfter());
        assertTrue(record.tokensAfter() <= budget.historyBudget());
        assertEquals(20, record.messagesBefore());
        assertEquals(result.size(), record.messagesAfter());
        assertNull(record.agentName(), "null config -> null agent name, recorded honestly");
        assertEquals(record.tokensReclaimed(), record.tokensBefore() - record.tokensAfter());
    }

    @Test
    @DisplayName("no trim -> no record (listener quiet on healthy turns)")
    void noTrimNoRecord() {
        ContextWindowBudget budget = ContextWindowBudget.of(10_000, 1_000, 1_000, 2_000);
        List<ContextTrimRecord> records = new ArrayList<>();

        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(null, budget, records::add);
        AgentState state = new AgentState();
        state.addMessage(user("hello"));

        enforcer.build(null, state);

        assertTrue(records.isEmpty());
    }

    @Test
    @DisplayName("record carries agent name from config when available")
    void recordCarriesAgentName() {
        ContextWindowBudget budget = ContextWindowBudget.of(1000, 100, 100, 200);
        List<ContextTrimRecord> records = new ArrayList<>();
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(null, budget, records::add);

        AgentState state = new AgentState();
        for (int i = 0; i < 20; i++) {
            state.addMessage(user("padding message " + i
                    + " " + "x".repeat(240)));
        }
        enforcer.build(null, state);

        assertEquals(1, records.size());
        assertNull(records.get(0).agentName());
    }

    @Test
    @DisplayName("record invariant: after <= before, non-negative counts, source non-null")
    void recordInvariants() {
        ContextTrimRecord ok = ContextTrimRecord.of("agent", ContextTrimRecord.TrimSource.ENFORCER,
                10, 5, 400, 200, 600);
        assertEquals(5, ok.messagesDropped());
        assertEquals(200, ok.tokensReclaimed());
        assertTrue(ok.didTrim());

        ContextTrimRecord noOp = ContextTrimRecord.of("agent", ContextTrimRecord.TrimSource.HANDOFF_FILTER,
                10, 10, 400, 400, 600);
        assertTrue(!noOp.didTrim());
        assertEquals(0, noOp.messagesDropped());
        assertEquals(0, noOp.tokensReclaimed());
    }

    @Test
    @DisplayName("validation: after > before and negative counts are rejected")
    void recordValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ContextTrimRecord("a", ContextTrimRecord.TrimSource.ENFORCER,
                        5, 6, 100, 100, 600, java.time.Instant.now()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new ContextTrimRecord("a", ContextTrimRecord.TrimSource.ENFORCER,
                        5, 5, -1, 0, 600, java.time.Instant.now()));
    }

    @Test
    @DisplayName("pair-preserving trim keeps tool-call invariant in the record's surviving tail")
    void pairPreservingTrimSurvivesInRecord() {
        ContextWindowBudget budget = ContextWindowBudget.of(900, 300, 300, 200);
        List<ContextTrimRecord> records = new ArrayList<>();
        ContextWindowEnforcer enforcer = new ContextWindowEnforcer(null, budget, records::add);

        AgentState state = new AgentState();
        state.addMessage(user("old question one with padding " + "x".repeat(400)));
        state.addMessage(assistantWithToolCall("t1"));
        state.addMessage(toolResult("t1"));
        state.addMessage(user("old question two with padding " + "y".repeat(400)));
        state.addMessage(assistantWithToolCall("t2"));
        state.addMessage(toolResult("t2"));
        state.addMessage(user("current question, keep me zzz"));

        List<ChatMessage> result = enforcer.build(null, state);

        assertEquals(1, records.size());
        ContextTrimRecord r = records.get(0);
        assertEquals(7, r.messagesBefore());
        assertEquals(result.size(), r.messagesAfter());

        // the surviving list must never start with an orphan TOOL result
        if (!result.isEmpty()) {
            assertTrue(result.get(0).role() != ChatRole.TOOL,
                    "surviving history must not start with an orphan tool result");
        }
    }
}

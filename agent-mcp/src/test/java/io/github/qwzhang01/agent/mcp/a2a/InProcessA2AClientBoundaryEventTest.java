package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 7: the A2A boundary emits one {@link BoundaryEvent} per
 * delegation — success, unknown recipient, peer failure — as a side
 * channel that never breaks the call, with structure only (task/recipient
 * ids, never payload).
 */
class InProcessA2AClientBoundaryEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Agent fixedAgent(String output) {
        return new Agent() {
            @Override public String run(String userInput) { return output; }
            @Override public String run(String userInput, AgentState state) { return output; }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    private static Agent errorAgent() {
        return new Agent() {
            @Override public String run(String userInput) { return "[Agent error: x]"; }
            @Override public String run(String userInput, AgentState state) {
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("model exploded");
                return "[Agent error: model exploded]";
            }
            @Override public AgentConfig getConfig() { return null; }
        };
    }

    @Test
    void successEmitsTaskSentWithLatency() {
        List<BoundaryEvent> events = new ArrayList<>();
        InProcessA2AClient client = new InProcessA2AClient(events::add)
                .registerAgent("researcher", fixedAgent("found it"), "research");

        client.sendTask(new A2ATask("t-1", "researcher", "research",
                MAPPER.createObjectNode().put("prompt", "find"), "supervisor", null));

        assertEquals(1, events.size());
        BoundaryEvent.A2ATaskSent e = assertInstanceOf(
                BoundaryEvent.A2ATaskSent.class, events.get(0));
        assertEquals("t-1", e.taskId());
        assertEquals("researcher", e.recipient());
        assertTrue(e.latencyMs() >= 0);
        assertEquals(1, e.schemaVersion());
    }

    @Test
    void unknownRecipientEmitsTaskFailed() {
        List<BoundaryEvent> events = new ArrayList<>();
        InProcessA2AClient client = new InProcessA2AClient(events::add);

        assertThrows(IllegalArgumentException.class, () -> client.sendTask(new A2ATask(
                "t-2", "ghost", "t", MAPPER.createObjectNode(), "supervisor", null)));

        assertEquals(1, events.size());
        BoundaryEvent.A2ATaskFailed e = assertInstanceOf(
                BoundaryEvent.A2ATaskFailed.class, events.get(0));
        assertEquals("UNKNOWN_RECIPIENT", e.failureKind());
        assertEquals("t-2", e.taskId());
        assertEquals("ghost", e.recipient());
    }

    @Test
    void peerErrorStateEmitsTaskFailed() {
        List<BoundaryEvent> events = new ArrayList<>();
        InProcessA2AClient client = new InProcessA2AClient(events::add)
                .registerAgent("broken", errorAgent());

        assertThrows(IllegalStateException.class, () -> client.sendTask(new A2ATask(
                "t-3", "broken", "t", MAPPER.createObjectNode(), "supervisor", null)));

        assertEquals(1, events.size());
        BoundaryEvent.A2ATaskFailed e = assertInstanceOf(
                BoundaryEvent.A2ATaskFailed.class, events.get(0));
        assertEquals("AGENT_FAILED", e.failureKind());
    }

    @Test
    void throwingSinkIsSwallowedNeverBreaksTheDelegation() {
        InProcessA2AClient client = new InProcessA2AClient(
                e -> { throw new IllegalStateException("sink boom"); })
                .registerAgent("w", fixedAgent("ok"));

        var result = client.sendTask(new A2ATask("t-4", "w", "t",
                MAPPER.createObjectNode().put("prompt", "p"), "supervisor", null));

        assertEquals("ok", result.get("output").asText(),
                "a throwing sink must never break the delegation");
    }

    @Test
    void legacyConstructorEmitsNothing() {
        InProcessA2AClient client = new InProcessA2AClient()
                .registerAgent("w", fixedAgent("ok"));

        var result = client.sendTask(new A2ATask("t-5", "w", "t",
                MAPPER.createObjectNode().put("prompt", "p"), "supervisor", null));

        assertEquals("ok", result.get("output").asText());
        assertEquals(A2ATaskStatus.COMPLETED, client.getTaskStatus("t-5"));
    }
}

package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 11 M11.4 tests: the in-process A2A client (protocol data model
 * round-trip, no real transport).
 */
class InProcessA2AClientTest {

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
    void sendTask_roundTripsThroughA2ADataModel() {
        InProcessA2AClient client = new InProcessA2AClient()
                .registerAgent("researcher", fixedAgent("found it"), "research");

        A2ATask task = new A2ATask("t-1", "researcher", "research",
                MAPPER.createObjectNode().put("prompt", "find libraries"), "supervisor", null);

        var result = client.sendTask(task);

        assertTrue(result.path("output").isTextual());
        assertEquals("found it", result.get("output").asText());
        assertEquals("completed", client.getTaskStatus("t-1"));
    }

    @Test
    void sendTask_promptFieldBecomesAgentInput() {
        List<String> inputs = new java.util.ArrayList<>();
        Agent recording = new Agent() {
            @Override public String run(String userInput) { return "ok"; }
            @Override public String run(String userInput, AgentState state) {
                inputs.add(userInput);
                return "ok";
            }
            @Override public AgentConfig getConfig() { return null; }
        };
        InProcessA2AClient client = new InProcessA2AClient().registerAgent("w", recording);

        client.sendTask(new A2ATask("t-2", "w", "t",
                MAPPER.createObjectNode().put("prompt", "hello a2a"), "supervisor", null));

        assertEquals(List.of("hello a2a"), inputs);
    }

    @Test
    void sendTask_unknownRecipient_throwsAndMarksFailed() {
        InProcessA2AClient client = new InProcessA2AClient();

        A2ATask task = new A2ATask("t-3", "ghost", "t",
                MAPPER.createObjectNode(), "supervisor", null);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> client.sendTask(task));
        assertTrue(e.getMessage().contains("ghost"));
        assertEquals("failed", client.getTaskStatus("t-3"));
    }

    @Test
    void sendTask_agentErrorState_throwsAndMarksFailed() {
        InProcessA2AClient client = new InProcessA2AClient()
                .registerAgent("broken", errorAgent());

        A2ATask task = new A2ATask("t-4", "broken", "t",
                MAPPER.createObjectNode().put("prompt", "x"), "supervisor", null);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> client.sendTask(task));
        assertTrue(e.getMessage().contains("model exploded"));
        assertEquals("failed", client.getTaskStatus("t-4"));
    }

    @Test
    void getTaskStatus_unknownTask_returnsUnknown() {
        assertEquals("unknown", new InProcessA2AClient().getTaskStatus("never-seen"));
    }

    @Test
    void discoverAgents_listsRegisteredCards() {
        InProcessA2AClient client = new InProcessA2AClient()
                .registerAgent("a", fixedAgent("x"), "research", "summarize")
                .registerAgent("b", fixedAgent("y"), "review");

        List<AgentCard> cards = client.discoverAgents();

        assertEquals(2, cards.size());
        assertEquals(List.of("research", "summarize"), cards.get(0).skills());
        assertEquals("in-process:a", cards.get(0).endpoint());
    }

    @Test
    void sendMessage_isFireAndForget_noException() {
        InProcessA2AClient client = new InProcessA2AClient();
        assertDoesNotThrow(() -> client.sendMessage(new A2AMessage(
                "m-1", "supervisor", "researcher",
                MAPPER.createObjectNode().put("text", "progress?"), null, "now")));
    }
}

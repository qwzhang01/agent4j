package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * D5: AgentState must survive a Jackson round-trip so AgentNode
 * can park it on the workflow blackboard and restore after a restart.
 */
class AgentStateJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripPreservesMessagesStepsAndStatus() throws Exception {
        AgentState original = new AgentState("hello");
        original.addMessage(ChatMessage.assistant("hi"));
        original.incrementStep();
        original.incrementStep();
        original.setMaxSteps(8);
        original.setStatus(AgentState.Status.DONE);
        original.setLastError(null);
        original.setLastActiveAgentName("specialist");

        AgentState restored = mapper.readValue(mapper.writeValueAsString(original), AgentState.class);

        assertEquals(original.getMessages(), restored.getMessages());
        assertEquals(2, restored.getCurrentStep());
        assertEquals(8, restored.getMaxSteps());
        assertEquals(AgentState.Status.DONE, restored.getStatus());
        assertNull(restored.getLastError());
        assertEquals("specialist", restored.getLastActiveAgentName());
    }

    @Test
    void missingLastActiveNameStaysNull() throws Exception {
        String json = "{\"messages\":[],\"currentStep\":0,\"maxSteps\":10,\"status\":\"IDLE\"}";
        AgentState restored = mapper.readValue(json, AgentState.class);
        assertNull(restored.getLastActiveAgentName());
    }

    @Test
    void snapshotIsIndependentCopy() {
        AgentState original = new AgentState("hello");
        original.setLastActiveAgentName("B");
        AgentState snap = original.snapshot();
        original.addMessage(ChatMessage.assistant("later"));
        original.setLastActiveAgentName("C");
        assertEquals(1, snap.getMessages().size());
        assertEquals(2, original.getMessages().size());
        assertEquals("B", snap.getLastActiveAgentName());
        assertEquals("C", original.getLastActiveAgentName());
    }
}

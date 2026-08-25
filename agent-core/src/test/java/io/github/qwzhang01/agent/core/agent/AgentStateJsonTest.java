package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Stage 6 D5: AgentState must survive a Jackson round-trip so AgentNode
 * can park it on the workflow blackboard and restore after a restart.
 */
class AgentStateJsonTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void roundTripPreservesMessagesStepsAndStatus() throws Exception {
        AgentState original = new AgentState("sys", "hello");
        original.addMessage(ChatMessage.assistant("hi"));
        original.incrementStep();
        original.incrementStep();
        original.setMaxSteps(8);
        original.setStatus(AgentState.Status.DONE);
        original.setLastError(null);

        AgentState restored = mapper.readValue(mapper.writeValueAsString(original), AgentState.class);

        assertEquals(original.getMessages(), restored.getMessages());
        assertEquals(2, restored.getCurrentStep());
        assertEquals(8, restored.getMaxSteps());
        assertEquals(AgentState.Status.DONE, restored.getStatus());
        assertNull(restored.getLastError());
    }

    @Test
    void snapshotIsIndependentCopy() {
        AgentState original = new AgentState("sys", "hello");
        AgentState snap = original.snapshot();
        original.addMessage(ChatMessage.assistant("later"));
        assertEquals(2, snap.getMessages().size());
        assertEquals(3, original.getMessages().size());
    }
}

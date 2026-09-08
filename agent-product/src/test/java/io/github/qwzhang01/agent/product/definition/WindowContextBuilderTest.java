package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Read-time windowing never owns instructions or mutates full history. */
class WindowContextBuilderTest {

    private AgentState stateWith(int count) {
        AgentState state = new AgentState();
        for (int i = 1; i <= count; i++) {
            state.addMessage(ChatMessage.user("m" + i));
        }
        return state;
    }

    @Test
    void shortHistoryPassesThroughUntouched() {
        AgentState state = stateWith(3);
        List<ChatMessage> window = new WindowContextBuilder(10).build(null, state);
        assertEquals(state.getMessages(), window);
        assertNotSame(state.getMessages(), window);
    }

    @Test
    void longHistoryKeepsExactlyRecentN() {
        AgentState state = stateWith(10);
        List<ChatMessage> window = new WindowContextBuilder(4).build(null, state);
        assertEquals(4, window.size());
        assertEquals("m7", window.get(0).content());
        assertEquals("m10", window.get(3).content());
        assertEquals(10, state.getMessages().size());
    }

    @Test
    void exactlyAtWindowSizePassesThrough() {
        AgentState state = stateWith(4);
        assertEquals(state.getMessages(), new WindowContextBuilder(4).build(null, state));
    }

    @Test
    void emptyHistoryProducesIndependentEmptyWindow() {
        AgentState state = new AgentState();
        var window = new WindowContextBuilder(1).build(null, state);
        assertTrue(window.isEmpty());
        assertNotSame(state.getMessages(), window);
    }

    @Test
    void nonPositiveMaxMessagesRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WindowContextBuilder(0));
        assertThrows(IllegalArgumentException.class, () -> new WindowContextBuilder(-1));
    }

    @Test
    void personaIsOutsideWindowAndNeverWrittenToHistory() {
        var model = new CapturingModel();
        AgentState state = stateWith(10);
        var config = new AgentConfig("window", "current persona", model, null, 5,
                new WindowContextBuilder(1));
        new SimpleAgent(config).run("latest", state);
        assertEquals(List.of(ChatMessage.system("current persona"), ChatMessage.user("latest")),
                model.request.messages());
        assertEquals(12, state.getMessages().size());
        assertTrue(state.getMessages().stream().noneMatch(m -> m.role() == ChatRole.SYSTEM));
    }

    private static class CapturingModel implements ModelClient {
        private ModelRequest request;

        @Override
        public ModelResponse chat(ModelRequest request) {
            this.request = request;
            return ModelResponse.text("ok");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }
}

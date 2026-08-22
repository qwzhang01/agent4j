package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the ReAct Agent Loop with an inline mock model client.
 * <p>
 * Uses a minimal inline mock so that agent-core is testable without
 * depending on agent-model module.
 */
class SimpleAgentTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void shouldReturnTextResponseWithoutTools() {
        var client = InlineMock.scripted()
                .addResponse(ModelResponse.text("Hello! I am a mock agent."));

        AgentConfig config = new AgentConfig("test", "system", client, null, 5);
        Agent agent = new SimpleAgent(config);

        String result = agent.run("Hi");
        assertEquals("Hello! I am a mock agent.", result);
    }

    @Test
    void shouldExecuteToolCallAndReturnFinalAnswer() {
        var args = mapper.createObjectNode().put("input", "test echo");

        var client = InlineMock.scripted()
                .addResponse(ModelResponse.toolCalls(
                        List.of(ToolCall.of("call_1", "echo", args))))
                .addResponse(ModelResponse.text("Echo result: Echo: test echo"));

        var registry = new InMemoryToolRegistry();
        registry.register(new EchoToolInline());

        AgentConfig config = new AgentConfig("test", "system", client, registry, 10);
        Agent agent = new SimpleAgent(config);

        String result = agent.run("Echo 'test echo'");
        assertEquals("Echo result: Echo: test echo", result);
    }

    @Test
    void shouldEnforceMaxSteps() {
        var args = mapper.createObjectNode().put("input", "loop");

        var client = InlineMock.scripted();
        for (int i = 0; i < 20; i++) {
            client.addResponse(ModelResponse.toolCalls(
                    List.of(ToolCall.of("call_" + i, "echo", args))));
        }

        var registry = new InMemoryToolRegistry();
        registry.register(new EchoToolInline());

        AgentConfig config = new AgentConfig("test", "system", client, registry, 3);
        Agent agent = new SimpleAgent(config);

        String result = agent.run("Keep echoing");
        assertTrue(result.contains("max steps"), "Should indicate max steps. Got: " + result);
    }

    @Test
    void shouldNotDuplicateUserMessageOnFreshRun() {
        var client = new CapturingMock();
        client.response = ModelResponse.text("ok");

        AgentConfig config = new AgentConfig("test", "you are a bot", client, null, 5);
        Agent agent = new SimpleAgent(config);
        agent.run("Hi");

        long userCount = client.lastRequest.messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .count();
        assertEquals(1, userCount, "fresh run() must not send the user message twice");
        assertEquals("Hi", client.lastRequest.messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .map(ChatMessage::content)
                .findFirst().orElseThrow());
    }

    @Test
    void shouldPassMultimodalUserMessageThrough() {
        var client = new CapturingMock();
        client.response = ModelResponse.text("a cat");

        AgentConfig config = new AgentConfig("test", "you are a bot", client, null, 5);
        Agent agent = new SimpleAgent(config);
        String result = agent.run(ChatMessage.userWithImage("what is this?", "https://example.com/cat.png"));

        assertEquals("a cat", result);
        ChatMessage user = client.lastRequest.messages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .findFirst().orElseThrow();
        assertEquals(2, user.parts().size());
    }

    // ============ Inline Mock ============

    /**
     * Minimal mock ModelClient for testing agent-core in isolation.
     */
    static class InlineMock implements ModelClient {
        private final Queue<ModelResponse> responses = new LinkedBlockingQueue<>();

        static InlineMock scripted() {
            return new InlineMock();
        }

        InlineMock addResponse(ModelResponse r) {
            responses.add(r);
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            if (responses.isEmpty()) {
                throw new RuntimeException("No more scripted responses");
            }
            return responses.poll();
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = chat(request);
            return java.util.stream.Stream.of(new StreamEvent.Done(r));
        }
    }

    static class CapturingMock implements ModelClient {
        ModelRequest lastRequest;
        ModelResponse response = ModelResponse.text("ok");

        @Override
        public ModelResponse chat(ModelRequest request) {
            lastRequest = request;
            return response;
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            return java.util.stream.Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    /**
     * Minimal echo tool for testing.
     */
    static class EchoToolInline implements Tool {
        @Override
        public String getName() {
            return "echo";
        }

        @Override
        public String getDescription() {
            return "Echoes input";
        }

        @Override
        public String getParametersSchema() {
            return "{}";
        }

        @Override
        public String execute(JsonNode arguments) {
            String input = arguments != null && arguments.has("input")
                    ? arguments.get("input").asText() : "(empty)";
            return "Echo: " + input;
        }
    }
}

package io.github.qwzhang01.agent.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;

import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 19: handoff as a loop-level config swap.
 * <p>
 * Verifies the P1 acceptance line: A→B→C in a single run; post-handoff
 * requests carry the new persona first and no SYSTEM in state; the step
 * budget is global; every tool call stays paired with a tool result;
 * handoff tools are exposed in the request schemas; an undeclared transfer
 * attempt falls back to the plain unknown-tool error path.
 */
class HandoffLoopTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ============ Mock ============

    /**
     * Scripted mock that also records every request it saw, so tests can
     * assert the persona and tool schemas of each turn — including after
     * a handoff swapped the active config.
     */
    static class RecordingScriptedMock implements ModelClient {
        final Queue<ModelResponse> responses = new LinkedBlockingQueue<>();
        final List<ModelRequest> requests = new ArrayList<>();

        RecordingScriptedMock addResponse(ModelResponse r) {
            responses.add(r);
            return this;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new RuntimeException("No more scripted responses");
            }
            return responses.poll();
        }

        @Override
        public java.util.stream.Stream<StreamEvent> stream(ModelRequest request) {
            return toStreamEvents(chat(request));
        }
    }

    static java.util.stream.Stream<StreamEvent> toStreamEvents(ModelResponse r) {
        if (r.content() != null && !r.content().isBlank()) {
            return java.util.stream.Stream.of(new StreamEvent.ContentDelta(r.content()), new StreamEvent.Done(r));
        }
        return java.util.stream.Stream.of(new StreamEvent.Done(r));
    }

    // ============ Helpers ============

    private AgentConfig config(String name, String persona, ModelClient client) {
        return new AgentConfig(name, persona, client, null, 10);
    }

    private AgentConfig config(String name, String persona, ModelClient client, List<HandoffSpec> handoffs) {
        return new AgentConfig(name, persona, client, null, 10, null, handoffs);
    }

    private ToolCall handoffCall(String id, String targetName) {
        return ToolCall.of(id, "transfer_to_" + targetName, (com.fasterxml.jackson.databind.JsonNode) null);
    }

    // ============ Tests ============

    @Test
    void shouldTransferThroughAbcChainAndKeepStatePersonaClean() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h2", "C"))));
        var clientC = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("final by C"));

        AgentConfig c = config("C", "You are C.", clientC);
        AgentConfig b = config("B", "You are B.", clientB, List.of(HandoffSpec.to(c)));
        AgentConfig a = config("A", "You are A.", clientA, List.of(HandoffSpec.to(b)));

        var state = new AgentState();
        new SimpleAgent(a).run("start", state);

        // Chain completed: C answered
        assertEquals("final by C", state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT && m.toolCalls() == null)
                .map(ChatMessage::content)
                .reduce((first, second) -> second).orElse(null));

        // A made 1 step, B made 1 step, C answered on step 3: global budget
        assertEquals(3, state.getCurrentStep());

        // Every model call after the handoff runs under the new persona
        assertEquals("You are B.", clientB.requests.get(0).messages().get(0).content());
        assertEquals("You are C.", clientC.requests.get(0).messages().get(0).content());

        // State stays persona-free: SYSTEM only ever appears in requests
        assertTrue(state.getMessages().stream().noneMatch(m -> m.role() == ChatRole.SYSTEM),
                "handoff must not write SYSTEM into AgentState");

        // Every assistant toolCall stays paired with a tool result
        for (ChatMessage m : state.getMessages()) {
            if (m.role() == ChatRole.ASSISTANT && m.toolCalls() != null) {
                for (ToolCall tc : m.toolCalls()) {
                    long paired = state.getMessages().stream()
                            .filter(x -> x.role() == ChatRole.TOOL && tc.id().equals(x.toolCallId()))
                            .count();
                    assertEquals(1, paired, "toolCall " + tc.id() + " must have exactly one tool result");
                }
            }
        }
    }

    @Test
    void shouldEmitHandoffEventWithFromToAndTool() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"));

        AgentConfig b = config("B", "You are B.", clientB);
        AgentConfig a = config("A", "You are A.", clientA, List.of(HandoffSpec.to(b)));

        List<AgentEvent> events = new ArrayList<>();
        new SimpleAgent(a).stream("start", new AgentState(), events::add);

        var handoff = events.stream()
                .filter(e -> e instanceof AgentEvent.Handoff)
                .map(e -> (AgentEvent.Handoff) e)
                .findFirst().orElseThrow();
        assertEquals("A", handoff.fromAgent());
        assertEquals("B", handoff.toAgent());
        assertEquals("transfer_to_B", handoff.toolName());

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Done));
    }

    @Test
    void shouldExposeHandoffSchemasInRequest() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("ok"));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("ok"));

        AgentConfig b = config("B", "You are B.", clientB);
        AgentConfig a = config("A", "You are A.", clientA, List.of(HandoffSpec.of(b, "escalate", "Escalate to B.")));

        new SimpleAgent(a).run("hi", new AgentState());

        List<String> tools = clientA.requests.get(0).tools();
        assertNotNull(tools, "handoff tools must be in the request");
        assertTrue(tools.stream().anyMatch(t -> t.contains("\"name\": \"escalate\"")
                        || t.contains("\"name\":\"escalate\"")),
                "custom handoff tool name must appear in schemas. Schemas: " + tools);
    }

    @Test
    void shouldRejectDegenerateHandoffSpecsAtAssembly() {
        // Null target is rejected up front by HandoffSpec itself.
        IllegalArgumentException nullTarget = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> HandoffSpec.of(null, "transfer_to_B", "desc"));
        assertTrue(nullTarget.getMessage().contains("target must not be null"));

        // Blank tool name is rejected too.
        var client = new RecordingScriptedMock().addResponse(ModelResponse.text("ok"));
        AgentConfig b = config("B", "You are B.", client);
        IllegalArgumentException blankName = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> HandoffSpec.of(b, "  ", "desc"));
        assertTrue(blankName.getMessage().contains("toolName must not be blank"));

        // A handoff tool name must not collide with a registered plain tool:
        // the loop intercepts handoffs first, so a collision would silently
        // shadow the plain tool.
        var registry = new io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry();
        registry.register(new NoopTool());
        IllegalArgumentException collision = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new AgentConfig("A", "You are A.", client, registry, 10, null,
                        List.of(HandoffSpec.of(b, "noop", "collides"))));
        assertTrue(collision.getMessage().contains("collides"));
    }

    @Test
    void shouldFallBackToUnknownToolWhenTargetNotDeclared() {
        // A declares NO handoffs; the model hallucinate-calls transfer_to_B
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))))
                .addResponse(ModelResponse.text("recovered"));

        AgentConfig a = config("A", "You are A.", clientA);

        var state = new AgentState();
        new SimpleAgent(a).run("start", state);

        // The undeclared transfer fell back to the unknown-tool error path,
        // then A recovered with a final answer — no config swap happened.
        ChatMessage toolResult = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL)
                .findFirst().orElseThrow();
        assertTrue(toolResult.content().contains("Tool not found: transfer_to_B"),
                "should hit unknown-tool path. Got: " + toolResult.content());
        assertEquals(AgentState.Status.DONE, state.getStatus());
        assertEquals("recovered", state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT && m.toolCalls() == null)
                .map(ChatMessage::content)
                .reduce((first, second) -> second).orElse(null));
    }

    @Test
    void shouldExecutePlainToolsBeforeHandoffInTheSameResponse() {
        // A declares a handoff to B; the response contains an echo tool call
        // AND the handoff call — the plain tool must run first and record.
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(
                        ToolCall.of("t1", "echo", mapper.createObjectNode().put("input", "hi")),
                        handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"));

        var registry = new io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry();
        registry.register(new SimpleAgentTest.EchoToolInline());

        AgentConfig b = config("B", "You are B.", clientB);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, registry, 10, null,
                List.of(HandoffSpec.to(b)));

        var state = new AgentState();
        new SimpleAgent(a).run("start", state);

        // echo ran before the transfer
        ChatMessage echoResult = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL && "t1".equals(m.toolCallId()))
                .findFirst().orElseThrow();
        assertEquals("Echo: hi", echoResult.content());

        // The handoff tool result references the model's tool_use id
        ChatMessage handoffResult = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL && "h1".equals(m.toolCallId()))
                .findFirst().orElseThrow();
        assertTrue(handoffResult.content().contains("transferred to agent 'B'"));
    }

    @Test
    void shouldKeepGlobalBudgetAcrossHandoff() {
        // A (maxSteps 3) hands to B whose config says maxSteps 1 — the
        // budget SSOT is the state, not the swapped config.
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock();
        for (int i = 0; i < 5; i++) {
            clientB.addResponse(ModelResponse.toolCalls(List.of(
                    ToolCall.of("b" + i, "noop", mapper.createObjectNode()))));
        }

        var registry = new io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry();
        registry.register(new NoopTool());

        AgentConfig b = new AgentConfig("B", "You are B.", clientB, registry, 1);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, registry, 3, null,
                List.of(HandoffSpec.to(b)));

        var state = new AgentState();
        String answer = new SimpleAgent(a).run("start", state);

        // A used 1 step; B's own maxSteps(1) is ignored — the GLOBAL budget
        // (3, set from the entry config) is what runs out.
        assertEquals(AgentState.Status.MAX_STEPS_EXCEEDED, state.getStatus());
        assertEquals(3, state.getCurrentStep());
        assertTrue(answer.contains("max steps"));
    }

    @Test
    void shouldResumeAsLastActiveAgentWhenReenteringEntry() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("done by B"))
                .addResponse(ModelResponse.text("still B"));

        AgentConfig b = config("B", "You are B.", clientB);
        AgentConfig a = config("A", "You are A.", clientA, List.of(HandoffSpec.to(b)));

        AgentState state = new AgentState();
        new SimpleAgent(a).run("start", state);
        assertEquals("B", state.getLastActiveAgentName());

        new SimpleAgent(a).run("follow-up", state);

        assertEquals(1, clientA.requests.size(), "entry persona must not answer the resumed turn");
        assertEquals(2, clientB.requests.size());
        assertEquals("You are B.", clientB.requests.get(1).messages().get(0).content());
        assertEquals("still B", state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.ASSISTANT && m.toolCalls() == null)
                .map(ChatMessage::content)
                .reduce((first, second) -> second).orElse(null));
    }

    @Test
    void shouldFailClosedWhenLastActiveNameIsUnreachable() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.text("should not run"));
        AgentConfig a = config("A", "You are A.", clientA);

        AgentState state = new AgentState();
        state.setLastActiveAgentName("ghost");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new SimpleAgent(a).run("hi", state));
        assertTrue(ex.getMessage().contains("ghost"));
        assertEquals(0, clientA.requests.size(), "unreachable identity must not call the model");
    }

    @Test
    void shouldUseTargetOwnExecutorAfterHandoff() {
        var clientA = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(handoffCall("h1", "B"))));
        var clientB = new RecordingScriptedMock()
                .addResponse(ModelResponse.toolCalls(List.of(
                        ToolCall.of("t1", "echo", mapper.createObjectNode().put("input", "hi")))))
                .addResponse(ModelResponse.text("done by B"));

        var registry = new io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry();
        registry.register(new SimpleAgentTest.EchoToolInline());
        RecordingExecutor recorded = new RecordingExecutor(new DefaultToolExecutor(registry));

        AgentConfig b = new AgentConfig("B", "You are B.", clientB, registry, 10, null,
                List.of(), null, recorded);
        AgentConfig a = new AgentConfig("A", "You are A.", clientA, null, 10, null,
                List.of(HandoffSpec.to(b)));

        AgentState state = new AgentState();
        new SimpleAgent(a).run("start", state);

        assertEquals(List.of("echo"), recorded.names);
        ChatMessage echoResult = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.TOOL && "t1".equals(m.toolCallId()))
                .findFirst().orElseThrow();
        assertEquals("Echo: hi", echoResult.content());
    }

    private static class RecordingExecutor implements ToolExecutor {
        final List<String> names = new ArrayList<>();
        private final ToolExecutor delegate;

        RecordingExecutor(ToolExecutor delegate) {
            this.delegate = delegate;
        }

        @Override
        public String execute(ToolCall toolCall) {
            names.add(toolCall.name());
            return delegate.execute(toolCall);
        }
    }

    private static class NoopTool implements io.github.qwzhang01.agent.core.tool.Tool {
        @Override
        public String getName() {
            return "noop";
        }

        @Override
        public String getDescription() {
            return "Does nothing";
        }

        @Override
        public String getParametersSchema() {
            return "{}";
        }

        @Override
        public String execute(com.fasterxml.jackson.databind.JsonNode arguments) {
            return "noop";
        }
    }

}

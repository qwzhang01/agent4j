package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming tests for {@link SimpleAgent} / {@link ReActAgentLoop}.
 * Reuses {@link SimpleAgentTest.InlineMock} (ContentDelta + Done when content is present).
 */
class SimpleAgentStreamTest {

    @Test
    void textOnlyEmitsDeltaThenDone() {
        var client = SimpleAgentTest.InlineMock.scripted()
                .addResponse(ModelResponse.text("Hello"));
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, null, 5));
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("Hi", state, events::add);

        // ModelCallStarted/ModelCallFinished pair around the deltas.
        assertEquals(4, events.size());
        AgentEvent.ModelCallStarted started = assertInstanceOf(AgentEvent.ModelCallStarted.class,
                events.get(0));
        assertEquals("test", started.agentName());
        assertEquals(1, started.step());
        assertEquals(new AgentEvent.ContentDelta("Hello"), events.get(1));
        AgentEvent.ModelCallFinished finished = assertInstanceOf(AgentEvent.ModelCallFinished.class,
                events.get(2));
        assertFalse(finished.failed());
        assertEquals(0, finished.toolCallCount());
        AgentEvent.Done done = assertInstanceOf(AgentEvent.Done.class, events.get(3));
        assertEquals("Hello", done.finalAnswer());
        assertEquals(AgentState.Status.DONE, done.state().getStatus());
        assertEquals(AgentState.Status.DONE, state.getStatus());
    }

    @Test
    void toolThenAnswerEmitsToolEventsThenDeltaAndDone() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode().put("input", "test echo");
        var client = SimpleAgentTest.InlineMock.scripted()
                .addResponse(ModelResponse.toolCalls(
                        List.of(ToolCall.of("call_1", "echo", args))))
                .addResponse(ModelResponse.text("Echo result: Echo: test echo"));

        var registry = new InMemoryToolRegistry();
        registry.register(new SimpleAgentTest.EchoToolInline());
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, registry, 10));
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("Echo 'test echo'", events::add);

        // ContentDelta DURING the model call (before ModelCallFinished):
        // Started, ToolCallCount-in-Finished... but deltas come first.
        // Actual order: [Started, delta, Finished(tool=1)] per call with
        // text, [Started, Finished(tool=1)] for the tool-calls response,
        // Tool pair between the two model calls, Done last.
        assertEquals(8, events.size());
        AgentEvent.ModelCallStarted call1 = assertInstanceOf(AgentEvent.ModelCallStarted.class,
                events.get(0));
        assertEquals(1, call1.step());
        AgentEvent.ModelCallFinished call1Done = assertInstanceOf(AgentEvent.ModelCallFinished.class,
                events.get(1));
        assertEquals(1, call1Done.toolCallCount(), "first call returned the tool call");
        assertFalse(call1Done.failed());
        AgentEvent.ToolStarted started = assertInstanceOf(AgentEvent.ToolStarted.class, events.get(2));
        assertEquals("echo", started.toolCall().name());
        AgentEvent.ToolFinished finished = assertInstanceOf(AgentEvent.ToolFinished.class, events.get(3));
        assertEquals("call_1", finished.toolCallId());
        assertEquals("echo", finished.toolName());
        assertEquals("Echo: test echo", finished.result());
        AgentEvent.ModelCallStarted call2 = assertInstanceOf(AgentEvent.ModelCallStarted.class,
                events.get(4));
        assertEquals(2, call2.step(), "second model call is step 2");
        assertEquals(new AgentEvent.ContentDelta("Echo result: Echo: test echo"), events.get(5));
        AgentEvent.ModelCallFinished call2Done = assertInstanceOf(AgentEvent.ModelCallFinished.class,
                events.get(6));
        assertEquals(0, call2Done.toolCallCount(), "second call returned plain text");
        AgentEvent.Done done = assertInstanceOf(AgentEvent.Done.class, events.get(7));
        assertEquals("Echo result: Echo: test echo", done.finalAnswer());
        assertEquals(AgentState.Status.DONE, done.state().getStatus());
    }

    @Test
    void maxStepsEmitsDoneWithPlaceholder() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var args = mapper.createObjectNode().put("input", "loop");
        var client = SimpleAgentTest.InlineMock.scripted();
        for (int i = 0; i < 20; i++) {
            client.addResponse(ModelResponse.toolCalls(
                    List.of(ToolCall.of("call_" + i, "echo", args))));
        }

        var registry = new InMemoryToolRegistry();
        registry.register(new SimpleAgentTest.EchoToolInline());
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, registry, 3));
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("Keep echoing", state, events::add);

        assertEquals(AgentState.Status.MAX_STEPS_EXCEEDED, state.getStatus());
        AgentEvent last = events.get(events.size() - 1);
        AgentEvent.Done done = assertInstanceOf(AgentEvent.Done.class, last);
        assertEquals(SimpleAgent.MAX_STEPS_PLACEHOLDER, done.finalAnswer());
    }

    @Test
    void throwingHostSinkDoesNotMarkModelFailed() {
        var client = SimpleAgentTest.InlineMock.scripted()
                .addResponse(ModelResponse.text("Hello"));
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, null, 5));
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("Hi", state, event -> {
            events.add(event);
            if (event instanceof AgentEvent.ContentDelta) {
                throw new IllegalStateException("ResponseBodyEmitter has already completed");
            }
        });

        assertEquals(AgentState.Status.DONE, state.getStatus());
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Done));
        assertTrue(events.stream().noneMatch(e -> e instanceof AgentEvent.Error),
                "a gone host sink must not be reported as a model failure");
    }

    @Test
    void streamErrorFromModelEmitsErrorAndSetsState() {
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", new ErrorStreamMock(), null, 5));
        AgentState state = new AgentState();
        List<AgentEvent> events = new ArrayList<>();

        agent.stream("Hi", state, events::add);

        // call, so order is Started, Error, Finished(failed=true) — the
        // Finished pair still closes the boundary even on failure.
        assertEquals(3, events.size());
        assertInstanceOf(AgentEvent.ModelCallStarted.class, events.get(0));
        assertInstanceOf(AgentEvent.Error.class, events.get(1));
        AgentEvent.ModelCallFinished finished = assertInstanceOf(AgentEvent.ModelCallFinished.class,
                events.get(2));
        assertTrue(finished.failed(), "stream failure must mark the model call failed");
        assertEquals(AgentState.Status.ERROR, state.getStatus());
        assertEquals("boom", state.getLastError());
    }

    @Test
    void secondStreamKeepsHistory() {
        var client = SimpleAgentTest.InlineMock.scripted()
                .addResponse(ModelResponse.text("first"))
                .addResponse(ModelResponse.text("second"));
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, null, 5));
        AgentState state = new AgentState();

        agent.stream("one", state, e -> {
        });
        agent.stream("two", state, e -> {
        });

        long userCount = state.getMessages().stream()
                .filter(m -> m.role() == ChatRole.USER)
                .count();
        assertTrue(userCount >= 2, "continuation must keep both user messages. got: " + userCount);
    }

    @Test
    void nullListenerThrowsNpe() {
        var client = SimpleAgentTest.InlineMock.scripted()
                .addResponse(ModelResponse.text("Hello"));
        Agent agent = new SimpleAgent(new AgentConfig("test", "system", client, null, 5));

        assertThrows(NullPointerException.class, () -> agent.stream("Hi", (Consumer<AgentEvent>) null));
    }

    @Test
    void defaultAgentFallbackProducesDone() {
        Agent stub = new Agent() {
            @Override
            public String run(String userInput) {
                return run(userInput, new AgentState());
            }

            @Override
            public String run(String userInput, AgentState state) {
                state.setStatus(AgentState.Status.DONE);
                return "ok";
            }

            @Override
            public AgentConfig getConfig() {
                return new AgentConfig("stub", "sys", null, null, 5);
            }
        };

        assertEquals("ok", stub.run(ChatMessage.user("hi")),
                "default run(ChatMessage) must degrade to run(String, state)");

        List<AgentEvent> events = new ArrayList<>();
        stub.stream("hi", events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Done),
                "default stream() must still produce Done");
        AgentEvent.Done done = (AgentEvent.Done) events.get(events.size() - 1);
        assertEquals("ok", done.finalAnswer());
    }

    /** Model that only emits {@link StreamEvent.Error} — never a Done. */
    static class ErrorStreamMock implements ModelClient {
        @Override
        public ModelResponse chat(ModelRequest request) {
            return ModelResponse.text("unused");
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Error("boom", null));
        }
    }
}

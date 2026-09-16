package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.run.RunEvent;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7.1 acceptance: the loop emits lifecycle facts to the ctx's
 * eventSink, in order, with run/trace correlation; legacy paths (no ctx,
 * or ctx without sink) stay bit-for-bit unchanged.
 */
class RunEventEmissionTest {

    @Test
    void successfulTwoStepRunEmitsOrderedLifecycleFacts() {
        List<RunEvent> events = new ArrayList<>();
        RunContext ctx = RunContext.builder()
                .runId("run-1")
                .traceId("trace-1")
                .eventSink(events::add)
                .build();

        ScriptedModel model = new ScriptedModel(List.of(
                ModelResponse.toolCalls(List.of(ToolCall.of("c1", "echo", "{}"))),
                ModelResponse.text("final")));
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new SimpleAgentTest.EchoToolInline());

        new SimpleAgent(new AgentConfig("test", "p", model, registry, 5), new ReActAgentLoop(registry))
                .run("hello", new AgentState(), ctx);

        // RunStarted, Step, Step, [tool step events], RunCompleted
        assertEquals(RunEvent.RunStarted.class, events.get(0).getClass());
        assertEquals("run-1", events.get(0).runId());
        assertEquals("trace-1", events.get(0).traceId());

        long steps = events.stream().filter(e -> e instanceof RunEvent.StepStarted).count();
        long stepsCompleted = events.stream().filter(e -> e instanceof RunEvent.StepCompleted).count();
        assertEquals(2, steps);
        assertEquals(2, stepsCompleted);

        RunEvent last = events.get(events.size() - 1);
        assertInstanceOf(RunEvent.RunCompleted.class, last);
        RunEvent.RunCompleted completed = (RunEvent.RunCompleted) last;
        assertEquals("run-1", completed.runId());
        assertEquals(2, completed.steps());

        // every event carries run + trace correlation
        for (RunEvent e : events) {
            assertEquals("run-1", e.runId());
            assertEquals("trace-1", e.traceId());
            assertEquals(1, e.schemaVersion());
        }
    }

    @Test
    void modelFailureEmitsStepCompletedWithModelFailureKindThenRunFailed() {
        List<RunEvent> events = new ArrayList<>();
        RunContext ctx = RunContext.builder().eventSink(events::add).build();

        ModelClient failing = new ModelClient() {
            @Override
            public ModelResponse chat(ModelRequest request) {
                throw new IllegalStateException("provider down");
            }

            @Override
            public Stream<StreamEvent> stream(ModelRequest request) {
                throw new IllegalStateException("provider down");
            }
        };

        new SimpleAgent(new AgentConfig("t", "p", failing, null, 5), new ReActAgentLoop(new InMemoryToolRegistry()))
                .run("hello", new AgentState(), ctx);

        RunEvent last = events.get(events.size() - 1);
        assertInstanceOf(RunEvent.RunFailed.class, last);
        assertEquals(FailureKind.MODEL_FAILURE, ((RunEvent.RunFailed) last).failureKind());

        RunEvent.StepCompleted step = events.stream()
                .filter(e -> e instanceof RunEvent.StepCompleted)
                .map(e -> (RunEvent.StepCompleted) e)
                .findFirst().orElseThrow();
        assertEquals(FailureKind.MODEL_FAILURE, step.failureKind());
    }

    @Test
    void cancelledRunEmitsRunCanceledNotRunFailed() {
        List<RunEvent> events = new ArrayList<>();
        io.github.qwzhang01.agent.core.run.CancellationToken cancelled =
                new io.github.qwzhang01.agent.core.run.CancellationToken() {
                    @Override
                    public boolean isCancelled() {
                        return true;
                    }

                    @Override
                    public void check() {
                        throw new io.github.qwzhang01.agent.core.run.RunCancelledException("user cancel");
                    }
                };
        RunContext ctx = RunContext.builder()
                .eventSink(events::add)
                .cancellationToken(cancelled)
                .build();

        new SimpleAgent(new AgentConfig("t", "p", new ScriptedModel(
                List.of(ModelResponse.text("x"))), null, 5),
                new ReActAgentLoop(new InMemoryToolRegistry()))
                .run("hello", new AgentState(), ctx);

        RunEvent last = events.get(events.size() - 1);
        assertInstanceOf(RunEvent.RunCanceled.class, last);
        assertEquals(2, events.size(), "RunStarted + RunCanceled only (cancel hit at first boundary)");
        assertEquals(RunEvent.RunStarted.class, events.get(0).getClass());
    }

    @Test
    void noCtxOrNullSinkRunsExactlyAsBefore() {
        // legacy: no ctx at all
        ScriptedModel model = new ScriptedModel(List.of(ModelResponse.text("ok")));
        AgentState legacy = new AgentState();
        new SimpleAgent(new AgentConfig("t", "p", model, null, 5)).run("hello", legacy);
        assertEquals(AgentState.Status.DONE, legacy.getStatus());

        // ctx without sink: runs fine, nobody listening
        AgentState quiet = new AgentState();
        new SimpleAgent(new AgentConfig("t", "p", new ScriptedModel(
                List.of(ModelResponse.text("ok"))), null, 5),
                new ReActAgentLoop(new InMemoryToolRegistry()))
                .run("hello", quiet, RunContext.create());
        assertEquals(AgentState.Status.DONE, quiet.getStatus());
    }

    @Test
    void throwingSinkDoesNotBreakTheRun() {
        RunContext ctx = RunContext.builder()
                .eventSink(e -> {
                    throw new IllegalStateException("sink broken");
                })
                .build();
        AgentState state = new AgentState();
        new SimpleAgent(new AgentConfig("t", "p", new ScriptedModel(
                List.of(ModelResponse.text("ok"))), null, 5),
                new ReActAgentLoop(new InMemoryToolRegistry()))
                .run("hello", state, ctx);
        assertEquals(AgentState.Status.DONE, state.getStatus());
    }

    @Test
    void childContextInheritsTheEventSink() {
        List<RunEvent> events = new ArrayList<>();
        RunContext parent = RunContext.builder()
                .runId("parent")
                .eventSink(events::add)
                .build();
        RunContext child = parent.deriveChild("child-agent");
        child.eventSink().accept(new RunEvent.RunStarted(child.runId(), child.traceId(),
                java.time.Instant.now()));
        assertEquals(1, events.size());
        assertEquals(child.runId(), events.get(0).runId());
        assertEquals(parent.traceId(), child.traceId(), "child shares the parent trace");
    }

    /** Model answering from a scripted list, one per call. */
    private static final class ScriptedModel implements ModelClient {
        private final List<ModelResponse> script;
        private int cursor;

        private ScriptedModel(List<ModelResponse> script) {
            this.script = script;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            return script.get(cursor++);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }
}

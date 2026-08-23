package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.trace.testsupport.RecordingTestSupport;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * THE Stage 14 D1 core proof: trajectory State == what the model actually saw
 * (post-ContextBuilder), which under compression DIFFERS from AgentState's
 * full history. Also covers step splitting, terminal capture, token
 * aggregation and verbatim observations (M14.1 verification list).
 * <p>
 * Wiring per the architecture note: Recording decorators OUTERMOST, an
 * independent {@code CapturingModelClient} between recording and mock as
 * non-circular evidence, MockModelClient scripted for deterministic runs.
 */
class RecordingFidelityTest {

    // ============ Helpers ============

    private static ToolCall call(String id, String input) {
        return ToolCall.of(id, "echo", "{\"input\":\"" + input + "\"}");
    }

    private static ToolRegistry echoRegistry() {
        var registry = new InMemoryToolRegistry();
        registry.register(new RecordingTestSupport.FakeTool("echo"));
        return registry;
    }

    /** Run the agent inside a manually managed session, return the trajectory. */
    private static Trajectory runInSession(Agent agent, TrajectoryRecorder recorder, String input) {
        var state = new AgentState();
        RunSession session = recorder.open(null);
        try {
            agent.run(input, state);
            return session.finish(state.getStatus(), state.getLastError());
        } finally {
            session.close(); // no-op when already finished
        }
    }

    // ============ D1: compression fidelity (the core test) ============

    @Test
    void compressionFidelityStateIsModelSeenNotFullHistory() {
        var mock = MockModelClient.scripted()
                .respond(new ModelResponse(null, List.of(call("c1", "a")), "tool_calls",
                        new ModelResponse.TokenUsage(100, 40, 140)))
                .respond(new ModelResponse(null, List.of(call("c2", "b")), "tool_calls",
                        new ModelResponse.TokenUsage(150, 50, 200)))
                .respondText("all done");
        var registry = echoRegistry();
        var recorder = new TrajectoryRecorder();
        var capturing = new RecordingTestSupport.CapturingModelClient(mock);
        var model = RecordingModelClient.wrap(capturing, recorder);
        var executor = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
        var config = new AgentConfig("fidelity-agent", "You are the system under test.",
                model, registry, 10, new RecordingTestSupport.TrimmingContextBuilder(2));
        Agent agent = new SimpleAgent(config, new ReActAgentLoop(executor));

        var state = new AgentState();
        RunSession session = recorder.open("run-fidelity");
        Trajectory trajectory;
        try {
            agent.run("hello", state);
        } finally {
            trajectory = session.finish(state.getStatus(), state.getLastError());
        }

        // three model calls captured
        assertEquals(3, capturing.requests.size());
        assertEquals(3, trajectory.steps().size());

        // D1: each step's state == the request the model actually received
        for (int i = 0; i < 3; i++) {
            assertEquals(capturing.requests.get(i), trajectory.steps().get(i).state(),
                    "step " + (i + 1) + " state must equal the captured model input");
        }

        // step 1 saw [system, user]; step 2 saw the TRIMMED window [assistant, tool]
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER),
                trajectory.steps().get(0).state().stream().map(ChatMessage::role).toList());
        var step2State = trajectory.steps().get(1).state();
        assertEquals(2, step2State.size());
        assertEquals(List.of(ChatRole.ASSISTANT, ChatRole.TOOL),
                step2State.stream().map(ChatMessage::role).toList());
        // ...and that is NOT the full history the loop kept in AgentState (7 messages)
        assertEquals(7, state.getMessages().size());
        assertNotEquals(state.getMessages(), step2State);

        // logical channel keeps the full conversation for trainers
        assertEquals(7, trajectory.messages().size());
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.TOOL,
                        ChatRole.ASSISTANT, ChatRole.TOOL, ChatRole.ASSISTANT),
                trajectory.messages().stream().map(ChatMessage::role).toList());
        assertEquals("all done", trajectory.messages().get(6).content());

        // terminal marking: only the last step is done
        assertFalse(trajectory.steps().get(0).done());
        assertNull(trajectory.steps().get(0).doneReason());
        assertTrue(trajectory.steps().get(2).done());
        assertEquals(DoneReason.DONE, trajectory.steps().get(2).doneReason());
        assertEquals(AgentState.Status.DONE, trajectory.status());
        assertNull(trajectory.metadata().lastError());

        // token aggregation over all model calls
        var usage = trajectory.metadata().tokenUsage();
        assertEquals(250, usage.promptTokens());
        assertEquals(90, usage.completionTokens());
        assertEquals(340, usage.totalTokens());

        // metadata timings present
        assertNotNull(trajectory.metadata().startedAt());
        assertNotNull(trajectory.metadata().finishedAt());
        assertTrue(trajectory.metadata().durationMs() >= 0);
    }

    // ============ D3: one step per model call, parallel tools in one step ============

    @Test
    void parallelToolCallsBelongToOneStep() {
        var mock = MockModelClient.scripted()
                .respond(new ModelResponse(null, List.of(call("c1", "x"), call("c2", "y")),
                        "tool_calls", null))
                .respondText("merged");
        var recorder = new TrajectoryRecorder();
        var registry = echoRegistry();
        var executor = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
        var model = RecordingModelClient.wrap(mock, recorder);
        var config = new AgentConfig("parallel-agent", "sys", model, registry, 10, null);
        Agent agent = new SimpleAgent(config, new ReActAgentLoop(executor));

        Trajectory trajectory = runInSession(agent, recorder, "two at once");
        assertEquals(2, trajectory.steps().size());
        List<ToolCall> firstActionCalls = trajectory.steps().get(0).action().toolCalls();
        assertEquals(2, firstActionCalls.size());
        assertEquals(2, trajectory.steps().get(0).observations().size());
        assertEquals("echo:x", trajectory.steps().get(0).observations().get(0).content());
        assertEquals("echo:y", trajectory.steps().get(0).observations().get(1).content());
        assertTrue(trajectory.steps().get(0).observations().stream().allMatch(o -> o.success()));
    }

    // ============ terminal capture ============

    @Test
    void modelErrorBecomesTerminalErrorStep() {
        var mock = MockModelClient.scripted(); // empty script -> chat throws ModelException
        var recorder = new TrajectoryRecorder();
        var registry = echoRegistry();
        var executor = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
        var model = RecordingModelClient.wrap(mock, recorder);
        var config = new AgentConfig("error-agent", "sys", model, registry, 10, null);
        Agent agent = new SimpleAgent(config, new ReActAgentLoop(executor));

        var state = new AgentState();
        RunSession session = recorder.open("run-err");
        Trajectory trajectory;
        try {
            agent.run("boom", state); // loop catches, returns "[Agent error: ...]"
        } finally {
            trajectory = session.finish(state.getStatus(), state.getLastError());
        }

        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertEquals(1, trajectory.steps().size());
        var step = trajectory.steps().get(0);
        assertEquals("error", step.action().finishReason());
        assertNull(step.action().content());
        assertTrue(step.done());
        assertEquals(DoneReason.ERROR, step.doneReason());
        // nothing entered the logical conversation beyond the leading state
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER),
                trajectory.messages().stream().map(ChatMessage::role).toList());
        // lastError captured from the loop's error text
        assertNotNull(trajectory.metadata().lastError());
        assertTrue(trajectory.metadata().lastError().contains("No more scripted responses"));
    }

    @Test
    void maxStepsExceededMarksLastStepDone() {
        var mock = MockModelClient.scripted()
                .respond(new ModelResponse(null, List.of(call("c1", "a")), "tool_calls", null))
                .respond(new ModelResponse(null, List.of(call("c2", "b")), "tool_calls", null));
        var recorder = new TrajectoryRecorder();
        var registry = echoRegistry();
        var executor = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
        var model = RecordingModelClient.wrap(mock, recorder);
        var config = new AgentConfig("max-agent", "sys", model, registry, 1, null);
        Agent agent = new SimpleAgent(config, new ReActAgentLoop(executor));

        var state = new AgentState();
        RunSession session = recorder.open("run-max");
        Trajectory trajectory;
        try {
            agent.run("loop forever", state);
        } finally {
            trajectory = session.finish(state.getStatus(), state.getLastError());
        }

        assertEquals(AgentState.Status.MAX_STEPS_EXCEEDED, trajectory.status());
        assertEquals(1, trajectory.steps().size());
        TrajectoryStep only = trajectory.steps().get(0);
        assertTrue(only.done());
        assertEquals(DoneReason.MAX_STEPS_EXCEEDED, only.doneReason());
        assertEquals(1, only.observations().size());
    }

    // ============ verbatim observations (D1: record what the model saw) ============

    @Test
    void toolErrorTextRecordedVerbatimAsSuccessfulObservation() {
        var mock = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("c1", "bomb", "{}"))
                .respondText("recovered");
        var recorder = new TrajectoryRecorder();
        var registry = new InMemoryToolRegistry();
        registry.register(new RecordingTestSupport.BombTool());
        var executor = RecordingToolExecutor.wrap(new DefaultToolExecutor(registry), recorder);
        var model = RecordingModelClient.wrap(mock, recorder);
        var config = new AgentConfig("bomb-agent", "sys", model, registry, 10, null);
        Agent agent = new SimpleAgent(config, new ReActAgentLoop(executor));

        Trajectory trajectory = runInSession(agent, recorder, "detonate");
        var observation = trajectory.steps().get(0).observations().get(0);
        // DefaultToolExecutor wraps the exception as "[ERROR] ..." text - the model
        // reads that text, so the trajectory keeps it verbatim and counts the
        // executor RETURN as success (honest narrow semantics)
        assertTrue(observation.content().startsWith("[ERROR]"));
        assertTrue(observation.content().contains("kaboom"));
        assertTrue(observation.success());
        assertEquals(AgentState.Status.DONE, trajectory.status());
    }
}

package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import io.github.qwzhang01.agent.trace.testsupport.RecordingTestSupport;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecordingAgent sugar + decorator transparency (M14.1 verification:
 * one-line wiring fills metadata from config; no-session passthrough;
 * executor exceptions recorded then rethrown; nested-open stand-down).
 */
class RecordingAgentTest {

    private static Agent wired(MockModelClient mock, TrajectoryRecorder recorder, String systemPrompt) {
        var registry = new InMemoryToolRegistry();
        registry.register(new RecordingTestSupport.FakeTool("echo"));
        return wired(mock, recorder,
                new io.github.qwzhang01.agent.core.tool.DefaultToolExecutor(registry), systemPrompt);
    }

    private static Agent wired(MockModelClient mock, TrajectoryRecorder recorder,
                               ToolExecutor innerExecutor, String systemPrompt) {
        var registry = new InMemoryToolRegistry();
        registry.register(new RecordingTestSupport.FakeTool("echo"));
        var model = RecordingModelClient.wrap(mock, recorder);
        var executor = RecordingToolExecutor.wrap(innerExecutor, recorder);
        return new SimpleAgent(
                new AgentConfig("sugar-agent", systemPrompt, model, registry, 10, null),
                new ReActAgentLoop(executor));
    }

    @Test
    void sugarFillsMetadataFromConfigAndProducesTrajectory() {
        var recorder = new TrajectoryRecorder();
        var mock = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("c1", "echo", "{\"input\":\"hi\"}"))
                .respondText("final answer");
        Agent agent = RecordingAgent.wrap(wired(mock, recorder, "You are SugarBot."), recorder);

        String answer = agent.run("hello");

        assertEquals("final answer", answer);
        assertEquals(1, recorder.completed().size());
        Trajectory trajectory = recorder.completed().get(0);

        // metadata enriched from the delegate's config
        assertEquals("sugar-agent", trajectory.metadata().agentName());
        assertEquals(TrajectoryMetadata.sha256Hex("You are SugarBot."),
                trajectory.metadata().promptSha256());
        assertEquals(List.of("echo"), trajectory.metadata().tools());
        assertEquals(10, trajectory.metadata().maxSteps());
        assertEquals(AgentState.Status.DONE, trajectory.status());
        assertNull(trajectory.reward());
        assertNull(trajectory.rewardSource());
        assertEquals(2, trajectory.steps().size());
        assertEquals("final answer",
                trajectory.messages().get(trajectory.messages().size() - 1).content());
    }

    @Test
    void decoratorsPassThroughWhenNoSessionOpen() {
        var recorder = new TrajectoryRecorder();
        var mock = MockModelClient.scripted().respondText("lonely");
        var model = RecordingModelClient.wrap(mock, recorder);
        var executor = RecordingToolExecutor.wrap(
                call -> "unused", recorder);

        ModelResponse response = model.chat(ModelRequest.builder()
                .addMessage(io.github.qwzhang01.agent.core.model.ChatMessage.user("outside run"))
                .build());
        assertEquals("lonely", response.content());
        assertEquals("unused", executor.execute(ToolCall.of("c1", "echo", "{}")));
        assertEquals(0, recorder.completed().size());
    }

    @Test
    void executorExceptionRecordedAsFailedObservationThenRethrown() {
        var recorder = new TrajectoryRecorder();
        ToolExecutor blowing = call -> {
            throw new IllegalStateException("executor blew up");
        };
        var mock = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("c1", "echo", "{\"input\":\"x\"}"))
                .respondText("never reached");
        Agent agent = RecordingAgent.wrap(wired(mock, recorder, blowing, "sys"), recorder);

        assertThrows(IllegalStateException.class, () -> agent.run("detonate executor"));

        // the trajectory is still assembled (finally-finish), observation captured
        assertEquals(1, recorder.completed().size());
        Trajectory trajectory = recorder.completed().get(0);
        var observation = trajectory.steps().get(0).observations().get(0);
        assertFalse(observation.success());
        assertTrue(observation.content().contains("EXECUTOR ERROR"));
        assertTrue(observation.content().contains("executor blew up"));
        // aborted run normalized to ERROR with an honest label
        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertTrue(trajectory.metadata().lastError().contains("non-terminal"));
    }

    @Test
    void recordingAgentStandsDownWhenSessionAlreadyOpen() {
        var recorder = new TrajectoryRecorder();
        var mock = MockModelClient.scripted()
                .respondToolCalls(ToolCall.of("c1", "echo", "{\"input\":\"x\"}"))
                .respondText("done");
        Agent inner = wired(mock, recorder, "sys");
        Agent agent = RecordingAgent.wrap(inner, recorder);

        RunSession session = recorder.open("outer-managed");
        String answer;
        try {
            answer = agent.run("via manual session");
        } finally {
            session.close();
        }

        assertEquals("done", answer);
        // exactly ONE trajectory - the manually opened session (no nested open crash)
        assertEquals(1, recorder.completed().size());
        assertEquals("outer-managed", recorder.completed().get(0).runId());
        assertEquals(2, recorder.completed().get(0).steps().size());
    }
}

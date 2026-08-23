package io.github.qwzhang01.agent.trace.testsupport;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.ToolObservation;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Hand-built trajectories for codec/sampler/reward tests - full control over
 * every field without running an agent (agent-driven paths are covered by
 * the M14.1 fidelity tests).
 */
public final class TrajectoryFixture {

    private TrajectoryFixture() {
    }

    /** A three-step success: tool round -> tool round -> final answer. */
    public static Trajectory successful(String runId) {
        var step1 = new TrajectoryStep(1,
                List.of(ChatMessage.system("sys"), ChatMessage.user("hello")),
                new StepAction(null,
                        List.of(ToolCall.of("c1", "echo", "{\"input\":\"a\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(100, 40, 140), 10),
                List.of(new ToolObservation("c1", "echo", "echo:a", true, 2)),
                null, false, null);
        var step2 = new TrajectoryStep(2,
                List.of(ChatMessage.assistantWithTools(null, step1.action().toolCalls()),
                        ChatMessage.tool("c1", "echo", "echo:a")),
                new StepAction(null,
                        List.of(ToolCall.of("c2", "lookup", "{\"q\":\"x\"}")),
                        "tool_calls", new ModelResponse.TokenUsage(150, 50, 200), 12),
                List.of(new ToolObservation("c2", "lookup", "[ERROR] boom", true, 3)),
                null, false, null);
        var step3 = new TrajectoryStep(3,
                List.of(ChatMessage.assistantWithTools(null, step2.action().toolCalls()),
                        ChatMessage.tool("c2", "lookup", "[ERROR] boom")),
                new StepAction("all done", null, "stop", new ModelResponse.TokenUsage(80, 20, 100), 8),
                List.of(), null, true, DoneReason.DONE);

        var metadata = new TrajectoryMetadata("fixture-agent",
                "deadbeef", List.of("echo", "lookup"), 10,
                Instant.parse("2026-08-24T00:00:00Z"), Instant.parse("2026-08-24T00:00:01Z"),
                1000, new ModelResponse.TokenUsage(330, 110, 440), null,
                Map.of("tenant", "acme"));

        var messages = List.of(
                ChatMessage.system("sys"), ChatMessage.user("hello"),
                ChatMessage.assistantWithTools(null, step1.action().toolCalls()),
                ChatMessage.tool("c1", "echo", "echo:a"),
                ChatMessage.assistantWithTools(null, step2.action().toolCalls()),
                ChatMessage.tool("c2", "lookup", "[ERROR] boom"),
                ChatMessage.assistant("all done"));

        return new Trajectory("traj-fixture-1", runId, metadata, AgentState.Status.DONE,
                List.of(step1, step2, step3), messages, null, null);
    }

    /** A one-step model failure: error terminal, nothing after [system, user]. */
    public static Trajectory failed(String runId) {
        var step = new TrajectoryStep(1,
                List.of(ChatMessage.system("sys"), ChatMessage.user("boom")),
                new StepAction(null, null, "error", null, 5),
                List.of(), null, true, DoneReason.ERROR);
        var metadata = new TrajectoryMetadata(null, null, List.of(), null,
                Instant.parse("2026-08-24T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z"),
                5, null, "ModelException: no scripted response", Map.of());
        return new Trajectory("traj-fixture-2", runId, metadata, AgentState.Status.ERROR,
                List.of(step), List.of(ChatMessage.system("sys"), ChatMessage.user("boom")),
                null, null);
    }

    /** Rebuild a trajectory with a different reward (for sampler tests). */
    public static Trajectory withReward(Trajectory trajectory, Double reward) {
        return new Trajectory(trajectory.trajectoryId(), trajectory.runId(), trajectory.metadata(),
                trajectory.status(), trajectory.steps(), trajectory.messages(), reward, "test");
    }

    // ============ same-prompt double rollout (M14.4 preference pairing) ============

    private static final List<ChatMessage> SHARED_PROMPT = List.of(
            ChatMessage.system("You are SupportBot."),
            ChatMessage.user("查订单 8842"));

    /** Good rollout of the shared prompt: one tool call, then a helpful answer. */
    public static Trajectory goodRollout(String runId) {
        var call = ToolCall.of("c1", "order-query", "{\"orderId\":\"8842\"}");
        var step1 = new TrajectoryStep(1, SHARED_PROMPT,
                new StepAction(null, List.of(call), "tool_calls",
                        new ModelResponse.TokenUsage(120, 40, 160), 15),
                List.of(new ToolObservation("c1", "order-query",
                        "{\"status\":\"shipped\"}", true, 30)),
                null, false, null);
        var step2 = new TrajectoryStep(2,
                List.of(ChatMessage.assistantWithTools(null, List.of(call)),
                        ChatMessage.tool("c1", "order-query", "{\"status\":\"shipped\"}")),
                new StepAction("订单 8842 已发货", null, "stop",
                        new ModelResponse.TokenUsage(200, 30, 230), 20),
                List.of(), null, true, DoneReason.DONE);
        return finishRollout("traj-good", runId, AgentState.Status.DONE,
                List.of(step1, step2), 1.0);
    }

    /** Bad rollout of the SAME prompt: refuses to help, no tools. */
    public static Trajectory badRollout(String runId) {
        var step = new TrajectoryStep(1, SHARED_PROMPT,
                new StepAction("抱歉，我查不到。", null, "stop",
                        new ModelResponse.TokenUsage(120, 15, 135), 8),
                List.of(), null, true, DoneReason.DONE);
        return finishRollout("traj-bad", runId, AgentState.Status.DONE,
                List.of(step), 0.2);
    }

    /** Silent failure of the SAME prompt: model died, no assistant reply at all. */
    public static Trajectory silentFailureRollout(String runId) {
        var step = new TrajectoryStep(1, SHARED_PROMPT,
                new StepAction(null, null, "error", null, 4),
                List.of(), null, true, DoneReason.ERROR);
        return finishRollout("traj-silent", runId, AgentState.Status.ERROR,
                List.of(step), -1.0);
    }

    private static Trajectory finishRollout(String trajectoryId, String runId,
                                            AgentState.Status status,
                                            List<TrajectoryStep> steps, double reward) {
        var metadata = new TrajectoryMetadata("support-bot", null, List.of("order-query"), 10,
                null, null, 0, null, null, Map.of());
        return new Trajectory(trajectoryId, runId, metadata, status, steps,
                io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps.logicalMessages(steps),
                reward, "test");
    }
}

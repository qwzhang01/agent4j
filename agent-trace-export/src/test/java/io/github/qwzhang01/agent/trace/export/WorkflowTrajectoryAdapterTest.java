package io.github.qwzhang01.agent.trace.export;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.replay.ReplayView;
import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fulfiling the Stage 5 javadoc promise: StepRecords -> Trajectory, and the
 * whole downstream pipeline (reward / sampling / JSONL / replay) works on
 * workflow runs unchanged (M14.3 verification).
 */
class WorkflowTrajectoryAdapterTest {

    @TempDir
    Path tempDir;

    private static WorkflowState stateWithThreeNodes() {
        var state = new WorkflowState("order-8842");
        state.record(StepRecord.success("fetch-order", 120, 1, "order found"));
        state.record(StepRecord.success("check-inventory", 80, 2, "3 in stock"));
        state.record(StepRecord.success("notify-user", 40, 1, "notified"));
        return state;
    }

    @Test
    void successfulRunMapsToNodeLevelTrajectory() {
        var state = stateWithThreeNodes();
        Trajectory trajectory = WorkflowTrajectoryAdapter.adapt("wf-run-1", state,
                ExecutionResult.success("done", state));

        assertEquals(3, trajectory.steps().size());
        assertEquals(AgentState.Status.DONE, trajectory.status());
        assertEquals(DoneReason.DONE, trajectory.doneReason());
        assertTrue(trajectory.steps().get(2).done());
        assertFalse(trajectory.steps().get(0).done());
        // node semantics: summary as action content, status as finish reason
        assertEquals("order found", trajectory.steps().get(0).action().content());
        assertEquals("SUCCESS", trajectory.steps().get(0).action().finishReason());
        // blackboard view grows: node 2 sees header + node 1's summary
        assertEquals(3, trajectory.steps().get(1).state().size());
        assertEquals(ChatRole.TOOL, trajectory.steps().get(1).state().get(2).role());
        // logical channel: header + one assistant message per node
        assertEquals(List.of(ChatRole.SYSTEM, ChatRole.USER,
                        ChatRole.ASSISTANT, ChatRole.ASSISTANT, ChatRole.ASSISTANT),
                trajectory.messages().stream().map(m -> m.role()).toList());
        assertEquals(240, trajectory.metadata().durationMs());
        assertNull(trajectory.metadata().lastError());
    }

    @Test
    void failedRunMapsErrorAndLastError() {
        var state = new WorkflowState("in");
        state.record(StepRecord.success("step-1", 10, 1, "ok"));
        state.record(StepRecord.failed("step-2", 30, 1, "db down"));
        Trajectory trajectory = WorkflowTrajectoryAdapter.adapt("wf-run-2", state,
                ExecutionResult.failed("step-2: db down", state));

        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertEquals(DoneReason.ERROR, trajectory.doneReason());
        assertEquals("step-2: db down", trajectory.metadata().lastError());
        assertEquals("FAILED", trajectory.steps().get(1).action().finishReason());
    }

    @Test
    void cancelledRunKeepsSemanticsInDoneReason() {
        var state = new WorkflowState("in");
        state.record(StepRecord.cancelled("wait-approval"));
        Trajectory trajectory = WorkflowTrajectoryAdapter.adapt("wf-run-3", state,
                ExecutionResult.cancelled(state));
        // AgentState.Status has no CANCELLED - status borrows the loop vocabulary,
        // the semantic lives in doneReason
        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertEquals(DoneReason.CANCELLED, trajectory.doneReason());
    }

    @Test
    void pausedRunIsNotAFinishedTrajectory() {
        var state = new WorkflowState("in");
        state.record(StepRecord.paused("wait-approval", "needs human"));
        var result = ExecutionResult.paused(null, state);
        assertThrows(IllegalArgumentException.class,
                () -> WorkflowTrajectoryAdapter.adapt("wf-run-4", state, result));
    }

    @Test
    void wholePipelineWorksOnWorkflowRunsUnchanged() throws IOException {
        // the payoff of the mapping: reward + sampling + JSONL + replay, all free
        var state = stateWithThreeNodes();
        Trajectory trajectory = WorkflowTrajectoryAdapter.adapt("wf-run-5", state,
                ExecutionResult.success("done", state));

        assertEquals(1.0, RuleReward.defaults().score(trajectory).reward());

        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        assertTrue(exporter.record(trajectory));

        Trajectory loaded = exporter.load().get(0);
        assertEquals(1.0, loaded.reward());
        assertEquals("rule", loaded.rewardSource());

        ReplayView view = ReplayView.of(loaded);
        assertEquals(3, view.stepCount());
        assertTrue(view.describeStep(2).contains("[DONE: DONE]"));
        // runId defaults to a generated workflow id when omitted
        assertTrue(WorkflowTrajectoryAdapter.adapt(null, state,
                ExecutionResult.success("x", state)).runId().startsWith("wf-run-"));
    }
}

package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 gap closure: plan-level resume on the durable stack. The gap
 * record said "plan-level checkpoint/resume is limited to
 * {@code completedFrom} blackboard inspection (no partial-plan cursor
 * persistence)" — that was HALF wrong: {@link PlanExecutor} lowers a plan
 * to a linear chain whose nodes ARE the steps, so the runtime's existing
 * cursor + checkpoint machinery already resumes a plan at step
 * granularity. What was missing is a test pinning the composition. These
 * tests do that: pause a plan mid-chain, checkpoint to disk, build a
 * FRESH RunManager (a restart), resume, and assert completed steps never
 * re-execute.
 * <p>
 * Honest limit kept (documented, not fixed here): crash recovery replays
 * from the LAST PAUSE, not the last completed node — the recovery
 * granularity is the pause, same as every other workflow run
 * (E8SideEffectGapExperimentTest scenario 6 pins this for plain nodes).
 */
class PlanResumeTest {

    @TempDir
    Path checkpointDir;

    /** Records execution + optional pause after recording. */
    private static final class RecordingStep implements PlanExecutor.StepExecutor {
        final List<String> executions = new ArrayList<>();
        private final String pauseAt;
        private final boolean pauseFirstTimeOnly;

        RecordingStep(String pauseAt) {
            this.pauseAt = pauseAt;
            this.pauseFirstTimeOnly = false;
        }

        @Override
        public String execute(Plan.Step step, Plan plan) throws RuntimeException {
            executions.add(step.id());
            if (step.id().equals(pauseAt) && !pauseFirstTimeOnly) {
                // pause on every entry would loop; the resume must NOT
                // re-enter this step — which is exactly what we assert.
            }
            return "out:" + step.id();
        }
    }

    /**
     * Step executor that pauses at a chosen step the FIRST time only.
     * The plan chain is linear; on resume the runtime re-executes from
     * the paused node (D2: cursor = the node to re-execute), so the
     * paused step's executor runs again on resume — it must NOT pause
     * the second time, or the run would never finish.
     */
    private static final class PauseOnceStep implements PlanExecutor.StepExecutor {
        final List<String> executions = new ArrayList<>();
        private final String pauseAt;
        private boolean pausedOnce = false;

        PauseOnceStep(String pauseAt) {
            this.pauseAt = pauseAt;
        }

        @Override
        public String execute(Plan.Step step, Plan plan) throws Exception {
            executions.add(step.id());
            if (step.id().equals(pauseAt) && !pausedOnce) {
                pausedOnce = true;
                throw new PauseException(step.id(), "plan paused mid-chain");
            }
            return "out:" + step.id();
        }
    }

    private static Plan threeStepPlan() {
        return Plan.of("resume-plan", java.util.List.of(
                new Plan.Step("gather", "gather inputs", java.util.List.of()),
                new Plan.Step("process", "process inputs", java.util.List.of("gather")),
                new Plan.Step("publish", "publish results", java.util.List.of("process"))));
    }

    // Scenario 1: pause mid-plan, resume from a fresh manager

    @Test
    @DisplayName("pause mid-plan: checkpoint to disk, fresh RunManager resumes without re-running completed steps")
    void pauseMidPlan_freshManagerResume_skipsCompletedSteps() throws IOException {
        Plan plan = threeStepPlan();
        PauseOnceStep executor = new PauseOnceStep("process");

        // ---- generation 1: gather runs, process pauses ----
        RunManager mgr1 = new RunManager(new FileCheckpointStore(checkpointDir));
        Workflow wf = new PlanExecutor().toWorkflow(plan, executor);
        ExecutionResult first = mgr1.start(wf, "in");
        assertTrue(first.isPaused(), "must pause at 'process', got: " + first.status());

        // The checkpoint IS on disk — a restart can find it
        assertTrue(Files.list(checkpointDir).findAny().isPresent(),
                "checkpoint files must exist in " + checkpointDir);

        // ---- generation 2: a fresh manager (simulated restart) resumes ----
        RunManager mgr2 = new RunManager(new FileCheckpointStore(checkpointDir));
        ExecutionResult resumed = mgr2.resume(first.resumeToken().runId(), wf);

        assertTrue(resumed.isSucceeded(), "resumed run must complete, got: " + resumed.status());

        // gather ran ONCE total (generation 1); process ran twice (pause
        // entry + resume re-entry — D2 cursor semantics); publish once.
        assertEquals(List.of("gather", "process", "process", "publish"),
                executor.executions,
                "completed steps never re-execute; the paused step re-enters once");
    }

    // Scenario 2: completedFrom reports the durable truth

    @Test
    @DisplayName("completedFrom on a resumed run's blackboard reports every step done")
    void completedFrom_reportsDurableTruth() throws IOException {
        Plan plan = threeStepPlan();
        PauseOnceStep executor = new PauseOnceStep("process");

        RunManager mgr1 = new RunManager(new FileCheckpointStore(checkpointDir));
        Workflow wf = new PlanExecutor().toWorkflow(plan, executor);
        ExecutionResult first = mgr1.start(wf, "in");
        assertTrue(first.isPaused());

        RunManager mgr2 = new RunManager(new FileCheckpointStore(checkpointDir));
        ExecutionResult resumed = mgr2.resume(first.resumeToken().runId(), wf);
        assertTrue(resumed.isSucceeded());

        // The durable blackboard (restored from the checkpoint) knows all
        // three steps completed — an executor resuming work asks
        // completedFrom and trusts the recorded outputs.
        WorkflowState board = resumed.state();
        Set<String> completed = new PlanExecutor().completedFrom(board, plan);
        assertEquals(Set.of("gather", "process", "publish"), completed);
        assertEquals("out:gather", board.get("gather"));
        assertEquals("out:process", board.get("process"));
        assertEquals("out:publish", board.get("publish"));
    }

    // Scenario 3: version drift refusal

    @Test
    @DisplayName("resume against a drifted plan (rebuildWith bumps planVersion) is refused by the runtime")
    void resumeAgainstDriftedPlan_isRefused() throws IOException {
        Plan v1 = threeStepPlan();
        PauseOnceStep executor = new PauseOnceStep("process");

        RunManager mgr1 = new RunManager(new FileCheckpointStore(checkpointDir));
        Workflow wfV1 = new PlanExecutor().toWorkflow(v1, executor);
        ExecutionResult first = mgr1.start(wfV1, "in");
        assertTrue(first.isPaused());

        // Same planId, EDITED steps: rebuildWith bumps planVersion 1 -> 2,
        // the lowered workflow version becomes v2 -> runtime refuses.
        Plan v2 = v1.rebuildWith(java.util.List.of(
                new Plan.Step("gather", "gather inputs (edited)", java.util.List.of()),
                new Plan.Step("process", "process inputs", java.util.List.of("gather")),
                new Plan.Step("publish", "publish results", java.util.List.of("process"))));
        assertEquals(2, v2.planVersion(), "rebuildWith must bump the version");
        Workflow wfV2 = new PlanExecutor().toWorkflow(v2, executor);

        RunManager mgr2 = new RunManager(new FileCheckpointStore(checkpointDir));
        ExecutionResult result = mgr2.resume(first.resumeToken().runId(), wfV2);

        assertFalse(result.isSucceeded(),
                "resume against a drifted plan identity must not succeed");
    }
}

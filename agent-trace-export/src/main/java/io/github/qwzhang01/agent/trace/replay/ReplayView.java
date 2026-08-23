package io.github.qwzhang01.agent.trace.replay;

import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.ToolObservation;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Step-through view over ONE trajectory, with integrity verification at
 * construction (Stage 14 D7: walk the recording, never re-run).
 * <p>
 * LLM calls are non-deterministic, so "replaying" a run faithfully is
 * impossible by definition; what replay MEANS here:
 * <ol>
 *   <li>verify the recorded structure is internally consistent (below)</li>
 *   <li>expose each step's exact model-seen state, action and observations
 *       for debugging ("why did the model call the wrong tool here") and
 *       for annotation browsing (M14.4)</li>
 * </ol>
 * Integrity checks (fail-fast, never guess):
 * <ul>
 *   <li>step indexes are consecutive 1..n</li>
 *   <li>{@code done} appears exactly once, on the last step (empty-step
 *       trajectories have none - legal, e.g. a run that never reached a
 *       model call); done steps must carry a doneReason, non-done must not</li>
 *   <li>the logical messages channel equals the reconstruction from steps
 *       ({@link io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps#logicalMessages})
 *       - the two channels are two views of one truth, and tampering with
 *       either shows up here</li>
 * </ul>
 */
public final class ReplayView {

    private final Trajectory trajectory;

    private ReplayView(Trajectory trajectory) {
        this.trajectory = trajectory;
        verify(trajectory);
    }

    /** Verify and wrap (throws IllegalArgumentException on any inconsistency). */
    public static ReplayView of(Trajectory trajectory) {
        return new ReplayView(trajectory);
    }

    public Trajectory trajectory() {
        return trajectory;
    }

    public int stepCount() {
        return trajectory.steps().size();
    }

    /** The exact messages the model saw on step i (0-based, full snapshot). */
    public List<ChatMessage> stateAt(int i) {
        return step(i).state();
    }

    public StepAction actionAt(int i) {
        return step(i).action();
    }

    public List<ToolObservation> observationsAt(int i) {
        return step(i).observations();
    }

    public boolean isDoneAt(int i) {
        return step(i).done();
    }

    /** Human-readable one-liner for step i (debug + annotation browsing). */
    public String describeStep(int i) {
        TrajectoryStep step = step(i);
        var action = step.action();
        String decision = action == null
                ? "no action"
                : action.hasToolCalls()
                        ? "calls " + action.toolCalls().size() + " tool(s): "
                                + action.toolCalls().stream().map(c -> c.name()).distinct().toList()
                        : "answers";
        return "step " + step.index() + ": sees " + step.state().size() + " msg(s), "
                + decision + ", " + step.observations().size() + " observation(s)"
                + (step.done() ? " [DONE: " + step.doneReason() + "]" : "");
    }

    private TrajectoryStep step(int i) {
        if (i < 0 || i >= stepCount()) {
            throw new IndexOutOfBoundsException("step index " + i + " out of 0.." + (stepCount() - 1));
        }
        return trajectory.steps().get(i);
    }

    // ============ Verification ============

    private static void verify(Trajectory trajectory) {
        List<TrajectoryStep> steps = trajectory.steps();
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).index() != i + 1) {
                throw new IllegalArgumentException("step indexes must be consecutive from 1: position "
                        + i + " has index " + steps.get(i).index());
            }
        }
        int doneCount = 0;
        for (int i = 0; i < steps.size(); i++) {
            TrajectoryStep step = steps.get(i);
            if (step.done()) {
                doneCount++;
                boolean last = i == steps.size() - 1;
                if (!last) {
                    throw new IllegalArgumentException("done must appear only on the last step; found at index "
                            + step.index());
                }
                if (step.doneReason() == null) {
                    throw new IllegalArgumentException("the done step (index " + step.index()
                            + ") must carry a doneReason");
                }
            } else if (step.doneReason() != null) {
                throw new IllegalArgumentException("non-done step (index " + step.index()
                        + ") must not carry a doneReason");
            }
        }
        if (doneCount > 1) {
            throw new IllegalArgumentException("done must appear at most once, found " + doneCount);
        }
        if (doneCount == 0 && !steps.isEmpty()) {
            throw new IllegalArgumentException("a non-empty trajectory must have exactly one done step "
                    + "(truncated? every recorder-finished run marks its last step done)");
        }
        List<ChatMessage> rebuilt = io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps
                .logicalMessages(steps);
        if (!rebuilt.equals(trajectory.messages())) {
            throw new IllegalArgumentException("messages channel is inconsistent with steps: rebuilt "
                    + rebuilt.size() + " message(s) but trajectory carries " + trajectory.messages().size());
        }
    }
}

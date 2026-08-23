package io.github.qwzhang01.agent.trace.replay;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.trace.export.TrajectoryExporter;
import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Walk-the-recording replay + the D7 integrity triple (M14.3 verification):
 * non-consecutive indexes / done not exactly-once-at-end / messages-steps
 * inconsistency all fail fast; file loading surfaces line numbers.
 */
class ReplayViewTest {

    @TempDir
    Path tempDir;

    @Test
    void walksARecordedTrajectoryStepByStep() {
        ReplayView view = ReplayView.of(TrajectoryFixture.successful("run-1"));
        assertEquals(3, view.stepCount());
        // step 2 saw the post-tool window [assistant, tool]
        assertEquals(List.of(ChatRole.ASSISTANT, ChatRole.TOOL),
                view.stateAt(1).stream().map(ChatMessage::role).toList());
        assertEquals("tool_calls", view.actionAt(1).finishReason());
        assertEquals(1, view.observationsAt(1).size());
        assertTrue(view.isDoneAt(2));
        assertFalse(view.isDoneAt(0));
        assertTrue(view.describeStep(2).contains("[DONE: DONE]"));
        assertTrue(view.describeStep(0).contains("calls 1 tool"));
    }

    @Test
    void nonConsecutiveIndexesRejected() {
        Trajectory good = TrajectoryFixture.successful("run-1");
        TrajectoryStep broken = new TrajectoryStep(7, good.steps().get(0).state(),
                good.steps().get(0).action(), good.steps().get(0).observations(),
                null, false, null);
        assertRejected(good, 0, broken, "consecutive");
    }

    @Test
    void doneInTheMiddleRejected() {
        Trajectory good = TrajectoryFixture.successful("run-1");
        TrajectoryStep broken = new TrajectoryStep(1, good.steps().get(0).state(),
                good.steps().get(0).action(), good.steps().get(0).observations(),
                null, true, DoneReason.DONE);
        assertRejected(good, 0, broken, "last step");
    }

    @Test
    void truncatedTrajectoryWithoutDoneRejected() {
        Trajectory good = TrajectoryFixture.successful("run-1");
        // drop the final (done) step -> 2 non-done steps, messages inconsistent too
        List<TrajectoryStep> truncated = List.of(good.steps().get(0), good.steps().get(1));
        Trajectory cut = rebuild(good, truncated, good.messages());
        assertThrows(IllegalArgumentException.class, () -> ReplayView.of(cut),
                "truncation must be detected");
    }

    @Test
    void doneWithoutReasonRejected() {
        Trajectory good = TrajectoryFixture.successful("run-1");
        TrajectoryStep broken = new TrajectoryStep(3, good.steps().get(2).state(),
                good.steps().get(2).action(), good.steps().get(2).observations(),
                null, true, null);
        assertRejected(good, 2, broken, "doneReason");
    }

    @Test
    void tamperedMessagesChannelRejected() {
        Trajectory good = TrajectoryFixture.successful("run-1");
        List<ChatMessage> tampered = new ArrayList<>(good.messages());
        tampered.add(ChatMessage.assistant("never happened"));
        assertThrows(IllegalArgumentException.class,
                () -> ReplayView.of(rebuild(good, good.steps(), tampered)),
                "messages must match the steps reconstruction");
    }

    @Test
    void emptyTrajectoryIsLegal() {
        Trajectory empty = new Trajectory("t", "r", null,
                io.github.qwzhang01.agent.core.agent.AgentState.Status.ERROR,
                List.of(), List.of(), null, null);
        assertEquals(0, ReplayView.of(empty).stepCount());
        assertThrows(IndexOutOfBoundsException.class, () -> ReplayView.of(empty).stateAt(0));
    }

    @Test
    void fileRoundTripLoadsVerifiedViews() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        exporter.record(TrajectoryFixture.successful("run-1"));
        exporter.record(TrajectoryFixture.failed("run-2"));

        var views = new TrajectoryReplayer().loadAll(exporter.file());
        assertEquals(2, views.size());
        assertEquals("run-1", views.get(0).trajectory().runId());
        assertEquals(-1.0, views.get(1).trajectory().reward());

        assertEquals("run-1", new TrajectoryReplayer().loadFirst(exporter.file())
                .trajectory().runId());
    }

    @Test
    void malformedLineFailsLoudWithLineNumber() throws IOException {
        Path file = tempDir.resolve("trajectories.jsonl");
        Files.writeString(file, "{not valid json\n");
        var error = assertThrows(IllegalArgumentException.class,
                () -> new TrajectoryReplayer().loadAll(file));
        assertTrue(error.getMessage().contains(":1"), "line number must be reported: " + error.getMessage());
    }

    // ============ helpers ============

    private static void assertRejected(Trajectory good, int position,
                                       TrajectoryStep replacedStep, String expectedFragment) {
        List<TrajectoryStep> steps = new ArrayList<>(good.steps());
        steps.set(position, replacedStep);
        // keep messages consistent where possible so the TARGET check fires first
        List<ChatMessage> messages = io.github.qwzhang01.agent.trace.trajectory.TrajectorySteps
                .logicalMessages(steps);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> ReplayView.of(rebuild(good, steps, messages)));
        assertTrue(error.getMessage().contains(expectedFragment),
                "message should contain '" + expectedFragment + "': " + error.getMessage());
    }

    private static Trajectory rebuild(Trajectory original, List<TrajectoryStep> steps,
                                      List<ChatMessage> messages) {
        return new Trajectory(original.trajectoryId(), original.runId(), original.metadata(),
                original.status(), steps, messages, original.reward(), original.rewardSource());
    }
}

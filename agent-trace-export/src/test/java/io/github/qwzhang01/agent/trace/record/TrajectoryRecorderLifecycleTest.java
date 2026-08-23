package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Recorder / session lifecycle rules (M14.1 verification: open/finish
 * semantics, runId uniqueness, honest non-terminal handling).
 */
class TrajectoryRecorderLifecycleTest {

    private final TrajectoryRecorder recorder = new TrajectoryRecorder();

    @Test
    void openGeneratesRunIdAndTrajectoryIdWhenNull() {
        RunSession session = recorder.open(null);
        session.close();
        Trajectory trajectory = recorder.completed().get(0);
        assertTrue(trajectory.runId().startsWith("run-"));
        assertTrue(trajectory.trajectoryId().startsWith("traj-"));
    }

    @Test
    void duplicateRunIdRejectedEvenAfterCompletion() {
        recorder.open("r1").close();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> recorder.open("r1"));
        assertTrue(e.getMessage().contains("r1"));
    }

    @Test
    void nestedOpenOnSameThreadRejected() {
        RunSession session = recorder.open("a");
        assertThrows(IllegalArgumentException.class, () -> recorder.open("b"));
        assertEquals(0, recorder.completed().size());
        session.close();
    }

    @Test
    void finishTwiceRejected() {
        RunSession session = recorder.open("r1");
        session.close();
        assertThrows(IllegalStateException.class,
                () -> session.finish(AgentState.Status.DONE, null));
    }

    @Test
    void closeWithoutExplicitFinishProducesHonestErrorTrajectory() {
        RunSession session = recorder.open("r1");
        session.close();
        Trajectory trajectory = recorder.completed().get(0);
        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertTrue(trajectory.metadata().lastError().contains("without explicit finish"));
        assertTrue(trajectory.steps().isEmpty());
    }

    @Test
    void nonTerminalStatusNormalizedToErrorWithHonestLabel() {
        RunSession session = recorder.open("r1");
        Trajectory trajectory = session.finish(AgentState.Status.RUNNING, null);
        assertEquals(AgentState.Status.ERROR, trajectory.status());
        assertTrue(trajectory.metadata().lastError().contains("non-terminal status RUNNING"));
    }

    @Test
    void completedListIsImmutableDefensiveCopy() {
        recorder.open("r1").close();
        assertThrows(UnsupportedOperationException.class,
                () -> recorder.completed().add(null));
    }

    @Test
    void attachAtMostOnce() {
        RunSession session = recorder.open("r1");
        AgentConfig config = new AgentConfig("a", "p", null, null);
        session.attach(config);
        assertThrows(IllegalArgumentException.class, () -> session.attach(config));
        session.close();
    }

    @Test
    void doneReasonMapsTerminalStatusesOnly() {
        assertEquals(DoneReason.DONE, DoneReason.from(AgentState.Status.DONE));
        assertEquals(DoneReason.MAX_STEPS_EXCEEDED, DoneReason.from(AgentState.Status.MAX_STEPS_EXCEEDED));
        assertEquals(DoneReason.ERROR, DoneReason.from(AgentState.Status.ERROR));
        assertNull(DoneReason.from(AgentState.Status.IDLE));
        assertNull(DoneReason.from(AgentState.Status.RUNNING));
        assertNull(DoneReason.from(AgentState.Status.EXECUTING_TOOL));
    }

    @Test
    void lastReturnsMostRecentTrajectory() {
        recorder.open("r1").close();
        recorder.open("r2").close();
        List<Trajectory> all = recorder.completed();
        assertEquals(2, all.size());
        assertEquals("r2", recorder.last().orElseThrow().runId());
    }
}

package io.github.qwzhang01.agent.trace.record;

import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Session manager and completed-trajectory holder (Stage 14 M14.1).
 * <p>
 * Wiring (the "assembly three-piece", architecture note §3.1):
 * <pre>{@code
 * TrajectoryRecorder recorder = new TrajectoryRecorder();
 * ModelClient model = RecordingModelClient.wrap(innerMost..., recorder);   // OUTERMOST
 * ToolExecutor exec = RecordingToolExecutor.wrap(rawExecutor, recorder);
 * Agent agent = RecordingAgent.wrap(new SimpleAgent(cfg(model), new ReActAgentLoop(exec)), recorder);
 * agent.run("...");                       // -> recorder.completed() has one Trajectory
 * }</pre>
 * <p>
 * Thread model (v1): sessions are thread-bound (ThreadLocal). One recorder
 * serves sequential runs on one thread; concurrent runs on different threads
 * need one recorder each (documented honest boundary - the shared-state race
 * lesson from Stage 12 §13 is not repeatable here).
 */
public final class TrajectoryRecorder {

    private final ThreadLocal<RecordingSession> current = new ThreadLocal<>();
    private final Set<String> usedRunIds = new HashSet<>();
    private final List<Trajectory> completedTrajectories = new ArrayList<>();

    /**
     * Open a recording session, bound to the calling thread.
     *
     * @param runId run identity; null auto-generates one. Reusing a runId
     *              (even after the earlier session finished) is rejected -
     *              one trajectory per run, duplicates would poison training data.
     * @throws IllegalArgumentException if a session is already open on this thread,
     *                                  or the runId was already recorded
     */
    public RunSession open(String runId) {
        if (current.get() != null) {
            throw new IllegalArgumentException(
                    "a recording session is already open on this thread - finish it before opening another (nested sessions are not supported in v1)");
        }
        String id = runId != null ? runId : "run-" + UUID.randomUUID();
        if (!usedRunIds.add(id)) {
            throw new IllegalArgumentException(
                    "runId '" + id + "' was already recorded - one trajectory per runId");
        }
        var session = new RecordingSession(this, id);
        current.set(session);
        return session;
    }

    /**
     * Completed trajectories so far, in finish order (defensive copy).
     */
    public List<Trajectory> completed() {
        return List.copyOf(completedTrajectories);
    }

    /**
     * The most recently finished trajectory, if any.
     */
    public Optional<Trajectory> last() {
        return completedTrajectories.isEmpty()
                ? Optional.empty()
                : Optional.of(completedTrajectories.get(completedTrajectories.size() - 1));
    }

    // ============ Package-visible plumbing ============

    RecordingSession currentSession() {
        return current.get();
    }

    void onSessionFinished(RecordingSession session, Trajectory trajectory) {
        if (current.get() == session) {
            current.remove();
        }
        completedTrajectories.add(trajectory);
    }
}

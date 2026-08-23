package io.github.qwzhang01.agent.trace.replay;

import io.github.qwzhang01.agent.trace.export.JsonlTrajectoryWriter;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Load trajectories for replay (Stage 14 D7): file -> verified step-through
 * views. Malformed JSON lines surface with their line number; structurally
 * inconsistent trajectories fail verification inside {@link ReplayView#of}.
 * Both are loud by design - replaying a guessed-at trajectory is worse than
 * refusing to replay at all.
 */
public final class TrajectoryReplayer {

    /** Load and verify every trajectory in a JSONL file (order preserved). */
    public List<ReplayView> loadAll(Path jsonlFile) throws IOException {
        List<ReplayView> views = new ArrayList<>();
        for (Trajectory trajectory : new JsonlTrajectoryWriter(jsonlFile).loadAll()) {
            views.add(ReplayView.of(trajectory));
        }
        return views;
    }

    /** Load and verify the FIRST trajectory in a JSONL file. */
    public ReplayView loadFirst(Path jsonlFile) throws IOException {
        List<Trajectory> all = new JsonlTrajectoryWriter(jsonlFile).loadAll();
        if (all.isEmpty()) {
            throw new IllegalArgumentException("no trajectory found in " + jsonlFile);
        }
        return ReplayView.of(all.get(0));
    }
}

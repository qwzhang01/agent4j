package io.github.qwzhang01.agent.trace.export;

import io.github.qwzhang01.agent.trace.reward.RewardSource;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.sample.TrajectorySampler;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Export facade (Stage 14 D4 + D5 + D8): score -> sample -> persist.
 * <p>
 * {@link #record} is the always-on path for finished runs:
 * rule/human reward attaches first, then the sampler decides whether the
 * (IO-expensive) persistence happens; rejections are counted, never silent.
 * {@link #write} bypasses reward and sampling for callers who want manual
 * control (re-exports, tests, tooling).
 * <p>
 * IO failures propagate - this exporter never drops a trajectory quietly
 * (fail loud, blueprint D4).
 */
public final class TrajectoryExporter {

    private final JsonlTrajectoryWriter writer;
    private final RewardSource rewardSource;
    private final TrajectorySampler sampler;
    private int skippedCount;

    /**
     * @param directory   export directory (created if absent; trajectories.jsonl inside)
     * @param rewardSource how runs are scored before sampling
     * @param policy      which scored trajectories get persisted
     */
    public TrajectoryExporter(Path directory, RewardSource rewardSource, SamplingPolicy policy) throws IOException {
        Files.createDirectories(directory);
        this.writer = new JsonlTrajectoryWriter(directory.resolve(JsonlTrajectoryWriter.FILE_NAME));
        this.rewardSource = rewardSource;
        this.sampler = new TrajectorySampler(policy);
    }

    /**
     * Record one finished trajectory: score it, let the sampler decide,
     * persist on acceptance.
     *
     * @return true if persisted; false if the sampler rejected it (counted)
     */
    public boolean record(Trajectory trajectory) throws IOException {
        Trajectory scored = rewardSource.score(trajectory).applyTo(trajectory);
        if (!sampler.shouldExport(scored)) {
            skippedCount++;
            return false;
        }
        writer.append(scored);
        return true;
    }

    /**
     * Persist a trajectory as-is (no reward, no sampling).
     */
    public Path write(Trajectory trajectory) throws IOException {
        return writer.append(trajectory);
    }

    /**
     * Load every trajectory this exporter's file contains (round-trip).
     */
    public List<Trajectory> load() throws IOException {
        return writer.loadAll();
    }

    public Path file() {
        return writer.file();
    }

    /** How many trajectories the sampler rejected so far (observable, D4). */
    public int skippedCount() {
        return skippedCount;
    }
}

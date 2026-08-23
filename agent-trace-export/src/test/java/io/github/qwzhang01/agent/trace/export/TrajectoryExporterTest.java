package io.github.qwzhang01.agent.trace.export;

import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Export facade end-to-end (M14.2 verification): record = score -> sample ->
 * persist, rejection counting, append ordering, loud IO failures.
 */
class TrajectoryExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void recordScoresSamplesPersistsAndRoundTrips() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        assertTrue(exporter.record(TrajectoryFixture.successful("run-1")));

        List<Trajectory> loaded = exporter.load();
        assertEquals(1, loaded.size());
        Trajectory trajectory = loaded.get(0);
        // reward attached by RuleReward before persistence
        assertEquals(1.0, trajectory.reward());
        assertEquals("rule", trajectory.rewardSource());
        // everything else survives the trip
        assertEquals(TrajectoryFixture.successful("run-1").steps(), trajectory.steps());
        assertEquals(TrajectoryFixture.successful("run-1").messages(), trajectory.messages());
        assertEquals(TrajectoryFixture.successful("run-1").metadata(), trajectory.metadata());
        assertEquals(0, exporter.skippedCount());
    }

    @Test
    void samplerRejectionSkipsPersistenceAndCounts() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.rate(0, 1L));
        assertFalse(exporter.record(TrajectoryFixture.successful("run-1")));
        assertEquals(1, exporter.skippedCount());
        assertFalse(Files.exists(exporter.file()));
        assertTrue(exporter.load().isEmpty());
    }

    @Test
    void failedTrajectoriesAreExportedByDefault() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        assertTrue(exporter.record(TrajectoryFixture.failed("run-err")));
        assertEquals(-1.0, exporter.load().get(0).reward());
    }

    @Test
    void appendsPreserveOrderAcrossRecords() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        exporter.record(TrajectoryFixture.successful("run-1"));
        exporter.record(TrajectoryFixture.failed("run-2"));
        exporter.record(TrajectoryFixture.successful("run-3"));

        List<Trajectory> loaded = exporter.load();
        assertEquals(3, loaded.size());
        assertEquals("run-1", loaded.get(0).runId());
        assertEquals("run-2", loaded.get(1).runId());
        assertEquals("run-3", loaded.get(2).runId());
    }

    @Test
    void writeBypassesRewardAndSampling() throws IOException {
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.rate(0, 1L));
        exporter.write(TrajectoryFixture.successful("run-manual"));
        Trajectory loaded = exporter.load().get(0);
        assertNull(loaded.reward());  // no scoring on the manual path
        assertEquals(0, exporter.skippedCount());
    }

    @Test
    void ioFailureIsLoudNeverSwallowed() throws IOException {
        Path blocker = tempDir.resolve("blocker");
        Files.createFile(blocker);  // a regular file where a directory is required
        assertThrows(IOException.class,
                () -> new TrajectoryExporter(blocker, RuleReward.defaults(), SamplingPolicy.all()));
    }
}

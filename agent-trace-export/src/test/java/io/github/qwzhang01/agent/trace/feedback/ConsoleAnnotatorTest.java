package io.github.qwzhang01.agent.trace.feedback;

import io.github.qwzhang01.agent.trace.export.TrajectoryExporter;
import io.github.qwzhang01.agent.trace.reward.RuleReward;
import io.github.qwzhang01.agent.trace.sample.SamplingPolicy;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Console annotation flow (M14.4 verification): sidecar lands as its own
 * file, the TRAJECTORY file's bytes are untouched (append-only proof),
 * skip/invalid input handling, prompt mismatch throws before interaction.
 */
class ConsoleAnnotatorTest {

    @TempDir
    Path tempDir;

    private ConsoleAnnotator annotator(String input, ByteArrayOutputStream consoleOut) throws IOException {
        return new ConsoleAnnotator(tempDir.resolve("annotations.jsonl"),
                new BufferedReader(new StringReader(input)),
                new PrintStream(consoleOut, true));
    }

    @Test
    void preferenceLandsInSidecarWithoutTouchingTrajectoryFile() throws IOException {
        // export a real trajectory file first, snapshot its bytes
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        var exporter = new TrajectoryExporter(tempDir, RuleReward.defaults(), SamplingPolicy.all());
        exporter.write(good);
        byte[] before = Files.readAllBytes(exporter.file());

        var console = new ByteArrayOutputStream();
        Optional<PreferencePair> pair = annotator("a\n", console).annotate(good, bad);

        assertTrue(pair.isPresent());
        assertEquals("A", pair.orElseThrow().preferred());
        // sidecar has exactly one line with the verdict
        List<String> sidecar = Files.readAllLines(tempDir.resolve("annotations.jsonl"));
        assertEquals(1, sidecar.size());
        assertTrue(sidecar.get(0).contains("\"preferred\":\"A\""));
        // APPEND-ONLY PROOF: trajectory file bytes unchanged by annotating
        assertArrayEquals(before, Files.readAllBytes(exporter.file()));
        // the interaction printed the walkthrough and both final answers
        var printed = console.toString();
        assertTrue(printed.contains("rollout A"));
        assertTrue(printed.contains("final answer: 订单 8842 已发货"));
        assertTrue(printed.contains("final answer: 抱歉，我查不到。"));
    }

    @Test
    void skipProducesNoSidecarLine() throws IOException {
        var console = new ByteArrayOutputStream();
        var result = annotator("skip\n", console).annotate(
                TrajectoryFixture.goodRollout("r1"), TrajectoryFixture.badRollout("r2"));
        assertTrue(result.isEmpty());
        assertFalse(Files.exists(tempDir.resolve("annotations.jsonl")));
    }

    @Test
    void invalidThenValidAnswer() throws IOException {
        var console = new ByteArrayOutputStream();
        var result = annotator("what?\nb\n", console).annotate(
                TrajectoryFixture.goodRollout("r1"), TrajectoryFixture.badRollout("r2"));
        assertEquals("B", result.orElseThrow().preferred());
        assertTrue(console.toString().contains("Unrecognized input"));
    }

    @Test
    void eofTreatedAsSkip() throws IOException {
        var console = new ByteArrayOutputStream();
        var result = annotator("", console).annotate(
                TrajectoryFixture.goodRollout("r1"), TrajectoryFixture.badRollout("r2"));
        assertTrue(result.isEmpty());
    }

    @Test
    void mismatchedPromptFailsBeforeInteraction() throws IOException {
        var console = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class, () -> annotator("a\n", console).annotate(
                TrajectoryFixture.goodRollout("r1"), TrajectoryFixture.successful("r2")));
        assertFalse(Files.exists(tempDir.resolve("annotations.jsonl")));
    }

    @Test
    void rateLandsHumanFeedbackInSidecar() throws IOException {
        var console = new ByteArrayOutputStream();
        HumanFeedback feedback = annotator("", console).rate(
                TrajectoryFixture.goodRollout("r1"), 5, "fast and correct");
        assertEquals(5, feedback.rating());
        assertTrue(Files.readString(tempDir.resolve("annotations.jsonl")).contains("HumanFeedback"));
    }
}

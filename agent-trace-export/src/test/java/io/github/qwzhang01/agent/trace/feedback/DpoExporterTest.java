package io.github.qwzhang01.agent.trace.feedback;

import io.github.qwzhang01.agent.trace.export.TrajectoryCodec;
import io.github.qwzhang01.agent.trace.testsupport.TrajectoryFixture;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DPO materialization (M14.4 verification): {prompt, chosen, rejected} shape,
 * direction correctness, dangling references fail loud, empty-response
 * suffixes are legal.
 */
class DpoExporterTest {

    @TempDir
    Path tempDir;

    private final TrajectoryCodec codec = new TrajectoryCodec();

    @Test
    void materializesPromptChosenRejectedWithCorrectDirection() throws IOException {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        var pair = TrajectoryPairBuilder.pair(good, bad, "A", "tester");

        var exporter = new DpoExporter(tempDir.resolve("preferences.jsonl"));
        exporter.export(List.of(pair), List.of(good, bad));

        JsonNode row = codec.toJsonNode(Files.readString(exporter.file()));
        assertEquals("v1", row.get("api_version").asText());
        assertEquals("PreferencePair", row.get("kind").asText());
        // prompt = shared prefix (2 messages)
        assertEquals(2, row.get("prompt").size());
        // chosen = good rollout's response (assistant tool_calls -> tool -> final)
        assertEquals(3, row.get("chosen").size());
        assertEquals("订单 8842 已发货", row.get("chosen").get(2).get("content").asText());
        // rejected = bad rollout's response (single refusal)
        assertEquals(1, row.get("rejected").size());
        assertEquals("抱歉，我查不到。", row.get("rejected").get(0).get("content").asText());
        assertEquals("A", row.get("metadata").get("preferred").asText());
        assertEquals("traj-good", row.get("metadata").get("traj_a").asText());
    }

    @Test
    void preferredBFlipsChosenAndRejected() throws IOException {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        var pair = TrajectoryPairBuilder.pair(good, bad, "B", "tester");
        var exporter = new DpoExporter(tempDir.resolve("preferences.jsonl"));
        exporter.export(List.of(pair), List.of(good, bad));

        JsonNode row = codec.toJsonNode(Files.readString(exporter.file()));
        assertEquals(1, row.get("chosen").size());   // bad side chosen now
        assertEquals(3, row.get("rejected").size());
    }

    @Test
    void danglingReferenceFailsLoud() {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        var pair = TrajectoryPairBuilder.pair(good, bad, "A", "tester");
        var exporter = new DpoExporter(tempDir.resolve("preferences.jsonl"));
        // pool missing 'bad' -> dangling reference, never a half-materialized row
        assertThrows(IllegalArgumentException.class,
                () -> exporter.export(List.of(pair), List.of(good)));
        assertFalse(Files.exists(exporter.file()));
    }

    @Test
    void silentFailureYieldsEmptyRejectedSuffix() throws IOException {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory silent = TrajectoryFixture.silentFailureRollout("r3");
        var pair = TrajectoryPairBuilder.pair(good, silent, "A", "tester");
        var exporter = new DpoExporter(tempDir.resolve("preferences.jsonl"));
        exporter.export(List.of(pair), List.of(good, silent));

        JsonNode row = codec.toJsonNode(Files.readString(exporter.file()));
        assertEquals(0, row.get("rejected").size());  // "no response" is a valid rejected half
        assertEquals(3, row.get("chosen").size());
    }

    @Test
    void appendsPreserveOrder() throws IOException {
        Trajectory good = TrajectoryFixture.goodRollout("r1");
        Trajectory bad = TrajectoryFixture.badRollout("r2");
        var p1 = TrajectoryPairBuilder.pair(good, bad, "A", "t");
        var p2 = TrajectoryPairBuilder.pair(bad, good, "B", "t");
        var exporter = new DpoExporter(tempDir.resolve("preferences.jsonl"));
        exporter.export(List.of(p1), List.of(good, bad));
        exporter.export(List.of(p2), List.of(good, bad));
        assertEquals(2, Files.readAllLines(exporter.file()).size());
    }
}

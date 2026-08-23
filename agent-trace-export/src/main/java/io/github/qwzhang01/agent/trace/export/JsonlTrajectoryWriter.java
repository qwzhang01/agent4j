package io.github.qwzhang01.agent.trace.export;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * JSONL file plumbing for trajectories (Stage 14 D8): one trajectory per
 * line, append-only, one file per exporter directory.
 * <p>
 * Failure semantics: IO problems and malformed lines throw (fail loud) - a
 * silently dropped or silently skipped trajectory is worse than an exception
 * the caller can see (D4, same lesson as the Stage 13 webhook idempotency
 * fix: silent loss is the worst outcome).
 */
public final class JsonlTrajectoryWriter {

    public static final String FILE_NAME = "trajectories.jsonl";

    private final Path file;
    private final TrajectoryCodec codec = new TrajectoryCodec();

    public JsonlTrajectoryWriter(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Append one trajectory as a single JSON line (created if absent).
     *
     * @throws IOException on IO failure - never swallowed
     */
    public Path append(Trajectory trajectory) throws IOException {
        String line = codec.toJson(trajectory).toString();
        Files.writeString(file, line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return file;
    }

    /**
     * Load every trajectory from the file (empty file = empty list; trailing
     * blank line tolerated). Malformed lines fail loud with the line number.
     */
    public List<Trajectory> loadAll() throws IOException {
        List<Trajectory> trajectories = new ArrayList<>();
        if (!Files.exists(file)) {
            return trajectories;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = codec.toJsonNode(line);
                trajectories.add(codec.fromJson(node));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "malformed trajectory at " + file + ":" + (i + 1) + " - " + e.getMessage(), e);
            }
        }
        return trajectories;
    }
}

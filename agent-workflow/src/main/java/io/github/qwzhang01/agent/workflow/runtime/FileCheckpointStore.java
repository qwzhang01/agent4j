package io.github.qwzhang01.agent.workflow.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * File-backed {@link CheckpointStore}: one JSON file per runId.
 * <p>
 * Survives process restart (unlike {@link InMemoryCheckpointStore}).
 * Values on the blackboard must be Jackson-serializable (String / Number /
 * Map / List for the teaching v1).
 */
public final class FileCheckpointStore implements CheckpointStore {

    private final Path dir;
    private final ObjectMapper mapper;

    public FileCheckpointStore(Path dir) {
        this.dir = dir;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String save(Checkpoint checkpoint) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(fileFor(checkpoint.runId()).toFile(), Snapshot.from(checkpoint));
            return checkpoint.checkpointId();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save checkpoint for " + checkpoint.runId(), e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        Path file = fileFor(runId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            Snapshot snap = mapper.readValue(file.toFile(), Snapshot.class);
            return Optional.of(snap.toCheckpoint());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load checkpoint for " + runId, e);
        }
    }

    @Override
    public void delete(String runId) {
        try {
            Files.deleteIfExists(fileFor(runId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public List<String> listRunIds() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path fileFor(String runId) {
        return dir.resolve(runId + ".json");
    }

    /**
     * Jackson-friendly snapshot of a Checkpoint. Public fields so the
     * default ObjectMapper can round-trip without extra mixins.
     */
    public static class Snapshot {
        public String checkpointId;
        public String runId;
        public String status;
        public String cursor;
        public Object input;
        public Map<String, Object> variables;
        public List<StepRecord> trace = new ArrayList<>();
        public long timestamp;
        public int stepsExecuted;
        public Object pendingInput;

        static Snapshot from(Checkpoint cp) {
            Snapshot s = new Snapshot();
            s.checkpointId = cp.checkpointId();
            s.runId = cp.runId();
            s.status = cp.status().name();
            s.cursor = cp.cursor();
            s.input = cp.state().getInput();
            s.variables = cp.state().getVariables();
            s.trace = new ArrayList<>(cp.state().getTrace());
            s.timestamp = cp.timestamp();
            s.stepsExecuted = cp.stepsExecuted();
            s.pendingInput = cp.pendingInput();
            return s;
        }

        Checkpoint toCheckpoint() {
            WorkflowState state = WorkflowState.restore(input, variables, trace);
            return new Checkpoint(
                    checkpointId, runId, RunState.valueOf(status), cursor,
                    state, timestamp, stepsExecuted, pendingInput);
        }
    }

    /** Best-effort recursive delete of the store directory (tests). */
    public static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}

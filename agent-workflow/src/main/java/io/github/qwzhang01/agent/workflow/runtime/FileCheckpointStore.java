package io.github.qwzhang01.agent.workflow.runtime;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * File-backed {@link CheckpointStore}: one JSON file per runId.
 * <p>
 * Survives process restart (unlike {@link InMemoryCheckpointStore}).
 * Values on the blackboard must be Jackson-serializable (String / Number /
 * Map / List for the teaching v1).
 * <p>
 * (harness roadmap) durability upgrades:
 * <ul>
 *   <li><b>Atomic save</b> — write to a temp file in the same directory,
 *       fsync the file, then atomic-rename onto the target. A crash
 *       mid-save leaves the previous checkpoint intact; readers never see
 *       a half-written file.</li>
 *   <li><b>runId validation</b> — ids are restricted to
 *       {@code [A-Za-z0-9._-]{1,128}} before any path is built, so a
 *       hostile or malformed runId can never traverse directories.</li>
 *   <li><b>Schema version round-trip</b> — the snapshot carries
 *       {@code schemaVersion}; unknown future versions are refused on
 *       load instead of silently misparsed.</li>
 * </ul>
 */
public final class FileCheckpointStore implements CheckpointStore {

    private static final Pattern SAFE_RUN_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

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
        validateRunId(checkpoint.runId());
        Path target = fileFor(checkpoint.runId());
        Path tmp = dir.resolve(checkpoint.runId() + ".json.tmp-"
                + Long.toHexString(System.currentTimeMillis()) + "-"
                + Thread.currentThread().getId());
        try {
            String json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(Snapshot.from(checkpoint));
            // 1) write temp file
            Files.writeString(tmp, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            // 2) fsync the file content (best effort on macOS, exact on Linux)
            try (var ch = java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            // 3) atomic rename onto the target
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return checkpoint.checkpointId();
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new UncheckedIOException("Failed to save checkpoint for " + checkpoint.runId(), e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        validateRunId(runId);
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
        validateRunId(runId);
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
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.replaceFirst("\\.json$", ""))
                    .filter(n -> SAFE_RUN_ID.matcher(n).matches())
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
     * Reject runIds that could escape the store directory: traversal
     * sequences, separators, wildcards, or absurd lengths.
     * Widened for {@code JdbcCheckpointStore} : the same
     * whitelist guards SQL-bound runIds.
     */
    public static void validateRunId(String runId) {
        if (runId == null || !SAFE_RUN_ID.matcher(runId).matches()) {
            throw new IllegalArgumentException(
                    "Illegal runId (must match [A-Za-z0-9._-]{1,128}): " + runId);
        }
    }

    /**
     * Jackson-friendly snapshot of a Checkpoint. Public fields so the
     * default ObjectMapper can round-trip without extra mixins.
     * <p>
     *  schemaVersion / workflowName / workflowVersion /
     * workflowHash / lastEventSeq fields ride along. Version 1 files
     * (missing schemaVersion) load with legacy identity "" hash = never
     * mismatches) — old checkpoints keep working, new ones are guarded.
     */
    public static class Snapshot {
        public int schemaVersion;
        public String checkpointId;
        public String runId;
        public String workflowName;
        public String workflowVersion;
        public String workflowHash;
        public String status;
        public String cursor;
        public Object input;
        public Map<String, Object> variables;
        public List<StepRecord> trace = new ArrayList<>();
        public long timestamp;
        public int stepsExecuted;
        public Object pendingInput;
        public long lastEventSeq;

        public static Snapshot from(Checkpoint cp) {
            Snapshot s = new Snapshot();
            s.schemaVersion = cp.schemaVersion();
            s.checkpointId = cp.checkpointId();
            s.runId = cp.runId();
            s.workflowName = cp.workflowName();
            s.workflowVersion = cp.workflowVersion();
            s.workflowHash = cp.workflowHash();
            s.status = cp.status().name();
            s.cursor = cp.cursor();
            s.input = cp.state().getInput();
            s.variables = cp.state().getVariables();
            s.trace = new ArrayList<>(cp.trace());
            s.timestamp = cp.timestamp();
            s.stepsExecuted = cp.stepsExecuted();
            s.pendingInput = cp.pendingInput();
            s.lastEventSeq = cp.lastEventSeq();
            return s;
        }

        public Checkpoint toCheckpoint() {
            if (schemaVersion > Checkpoint.SCHEMA_VERSION) {
                throw new IllegalStateException("Checkpoint schema version " + schemaVersion
                        + " is newer than this runtime understands ("
                        + Checkpoint.SCHEMA_VERSION + ") for run " + runId);
            }
            WorkflowState state = WorkflowState.restore(input, variables, trace);
            return new Checkpoint(
                    schemaVersion <= 0 ? 1 : schemaVersion,
                    checkpointId, runId,
                    workflowName == null ? "" : workflowName,
                    workflowVersion == null ? "" : workflowVersion,
                    workflowHash == null ? "" : workflowHash,
                    RunState.valueOf(status), cursor,
                    state, timestamp, stepsExecuted, pendingInput, lastEventSeq, trace);
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

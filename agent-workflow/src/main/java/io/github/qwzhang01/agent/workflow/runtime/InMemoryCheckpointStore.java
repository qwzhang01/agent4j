package io.github.qwzhang01.agent.workflow.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory CheckpointStore for tests and ephemeral runs.
 * <p>
 * Checkpoints are lost when the JVM exits. Use {@link FileCheckpointStore}
 * for crash recovery demos.
 */
public final class InMemoryCheckpointStore implements CheckpointStore {

    private final Map<String, Checkpoint> store = new ConcurrentHashMap<>();

    @Override
    public String save(Checkpoint checkpoint) {
        store.put(checkpoint.runId(), checkpoint);
        return checkpoint.checkpointId();
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        return Optional.ofNullable(store.get(runId));
    }

    @Override
    public void delete(String runId) {
        store.remove(runId);
    }

    @Override
    public List<String> listRunIds() {
        return List.copyOf(store.keySet());
    }
}

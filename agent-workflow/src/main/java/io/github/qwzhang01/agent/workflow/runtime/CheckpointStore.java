package io.github.qwzhang01.agent.workflow.runtime;

import java.util.List;
import java.util.Optional;

/**
 * Persistent storage for {@link Checkpoint}s.
 * <p>
 * Design decision (D6): v1 ships InMemory + File implementations.
 * The interface is ready for database backends (Stage 18 or production).
 * <p>
 * Keyed by runId: one latest checkpoint per Run.
 */
public interface CheckpointStore {

    /** Save a checkpoint, return its checkpointId. */
    String save(Checkpoint checkpoint);

    /** Load the latest checkpoint for a run. */
    Optional<Checkpoint> load(String runId);

    /** Delete all checkpoints for a run. */
    void delete(String runId);

    /** List all runIds that have checkpoints. */
    List<String> listRunIds();
}

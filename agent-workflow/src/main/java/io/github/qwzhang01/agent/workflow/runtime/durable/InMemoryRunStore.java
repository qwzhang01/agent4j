package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference in-memory {@link RunStore} (tests, demos). Same contract as the
 * file/JDBC backends — semantics proven here, ported to disk/SQL later.
 */
public final class InMemoryRunStore implements RunStore {

    private final Map<String, RunRecord> rows = new ConcurrentHashMap<>();

    @Override
    public RunRecord create(RunRecord record) {
        RunRecord existing = rows.putIfAbsent(record.runId(), record);
        if (existing != null) {
            throw new VersionConflictException(
                    "Run '" + record.runId() + "' already exists", existing);
        }
        return record;
    }

    @Override
    public synchronized RunRecord update(RunRecord record) {
        RunRecord stored = rows.get(record.runId());
        if (stored == null) {
            throw new VersionConflictException("Run '" + record.runId() + "' not found", null);
        }
        if (record.version() != stored.version()) {
            throw new VersionConflictException("Version conflict on run '" + record.runId()
                    + "': carried " + record.version() + ", stored " + stored.version(), stored);
        }
        RunRecord next = new RunRecord(
                record.runId(), record.workflowName(), record.workflowVersion(),
                record.workflowHash(), record.status(), record.cursor(),
                record.stepsExecuted(), record.lastEventSeq(), record.checkpointId(),
                record.errorMessage(), record.createdAt(), System.currentTimeMillis(),
                stored.version() + 1, record.lastTrace());
        rows.put(record.runId(), next);
        return next;
    }

    @Override
    public Optional<RunRecord> get(String runId) {
        return Optional.ofNullable(rows.get(runId));
    }

    @Override
    public List<RunRecord> listRecoveryCandidates() {
        return rows.values().stream().filter(RunRecord::isRecoveryCandidate).toList();
    }

    @Override
    public List<RunRecord> listByStatus(String status) {
        return rows.values().stream()
                .filter(r -> r.status().equals(status)).toList();
    }

    @Override
    public List<RunRecord> listAll() {
        return List.copyOf(rows.values());
    }
}

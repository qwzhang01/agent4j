package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.util.List;
import java.util.Optional;

/**
 * Durable run registry (, harness roadmap).
 * <p>
 * "Scheduler only schedules persistent Runs, JVM memory is not the only
 * truth": every state transition lands here with an optimistic version,
 * and recovery candidates come from {@link #listRecoveryCandidates}, not
 * from an activeRuns map.
 */
public interface RunStore {

    /** Insert a fresh row (version 0). Fails on duplicate runId. */
    RunRecord create(RunRecord record);

    /**
     * Optimistic-locked update. Rejects when the carried version does not
     * match the stored one — two racing workers, one loses loudly.
     */
    RunRecord update(RunRecord record) throws VersionConflictException;

    Optional<RunRecord> get(String runId);

    /** RUNNING / PAUSED / WAITING_APPROVAL rows — the restart sweep list. */
    List<RunRecord> listRecoveryCandidates();

    /** Rows in a given status (any status name, for diagnostics). */
    List<RunRecord> listByStatus(String status);

    /** All rows (admin / test inspection). */
    List<RunRecord> listAll();
}

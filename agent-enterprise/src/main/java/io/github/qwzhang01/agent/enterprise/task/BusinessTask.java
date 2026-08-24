package io.github.qwzhang01.agent.enterprise.task;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * An enterprise business task: the business-level projection of workflow runs
 * (Stage 15 M15.4, D7).
 * <p>
 * A task is NOT a run. Runs are technical executions indexed by runId; users
 * and supervisors index business work ("refund ticket T-0001"). One task maps
 * to one or more runs (submit-run, resume of the same run, a retry run), the
 * current run being the last entry of {@code runIds}. Approvals hang on the
 * TASK, not the run - the supervisor approves the business, not the execution.
 * <p>
 * Status machine (mapped from {@code ExecutionResult.Status} after each
 * lifecycle call):
 * <pre>
 * SUBMITTED -> RUNNING -> WAITING_APPROVAL (run paused at approval node)
 *                       -> DONE / FAILED (terminal after resume)
 *           -> CANCELLED (supervisor rejected: cancel + settle)
 * </pre>
 * v1 honest boundary: PAUSED is always interpreted as WAITING_APPROVAL - in
 * this profile the only thing that pauses a run is a human approval node
 * (Stage 7 fire/timer pauses have a different enterprise semantic, out of
 * scope). Immutable record + wither derivations; the
 * {@link EnterpriseTaskManager} owns all transitions.
 *
 * @param taskId      business identifier ("T-0001")
 * @param tenantId    owning tenant (isolation)
 * @param submitterId who submitted the task (audit attribution)
 * @param description business description
 * @param status      current lifecycle status
 * @param runIds      run history, newest last; empty until the first run starts
 * @param approvals   approval records in order (the audit trail of decisions)
 * @param createdAt   submission time
 * @param updatedAt   last transition time
 */
public record BusinessTask(
        String taskId,
        String tenantId,
        String submitterId,
        String description,
        Status status,
        List<String> runIds,
        List<TaskApprovalRecord> approvals,
        Instant createdAt,
        Instant updatedAt
) {

    /** Lifecycle of a business task. */
    public enum Status {
        SUBMITTED, RUNNING, WAITING_APPROVAL, DONE, FAILED, CANCELLED;

        /** Terminal states never transition again. */
        public boolean isTerminal() {
            return this == DONE || this == FAILED || this == CANCELLED;
        }
    }

    public BusinessTask {
        requireText(taskId, "taskId");
        requireText(tenantId, "tenantId");
        requireText(submitterId, "submitterId");
        requireText(description, "description");
        Objects.requireNonNull(status, "status must not be null");
        runIds = runIds == null ? List.of() : List.copyOf(runIds);
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    // ============ Derived Accessors ============

    /**
     * The run currently driving this task (last of the history), or null
     * before the first run starts.
     */
    public String currentRunId() {
        return runIds.isEmpty() ? null : runIds.get(runIds.size() - 1);
    }

    /**
     * Whether the task is in a terminal state (no further transitions).
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    // ============ Wither Derivations ============

    BusinessTask withRun(String runId) {
        return new BusinessTask(taskId, tenantId, submitterId, description,
                status, append(runIds, runId), approvals, createdAt, Instant.now());
    }

    BusinessTask withStatus(Status newStatus) {
        return new BusinessTask(taskId, tenantId, submitterId, description,
                newStatus, runIds, approvals, createdAt, Instant.now());
    }

    BusinessTask withApproval(TaskApprovalRecord record) {
        return new BusinessTask(taskId, tenantId, submitterId, description,
                status, runIds, append(approvals, record), createdAt, Instant.now());
    }

    // ============ Helpers ============

    private static <T> List<T> append(List<T> list, T item) {
        java.util.ArrayList<T> out = new java.util.ArrayList<>(list);
        out.add(item);
        return List.copyOf(out);
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

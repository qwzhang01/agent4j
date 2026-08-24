package io.github.qwzhang01.agent.enterprise.task;

import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.workflow.ApprovalService;
import io.github.qwzhang01.agent.workflow.ExecutionResult;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.Run;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business task lifecycle manager (Stage 15 M15.4).
 * <p>
 * Bridges two layers that index work differently: supervisors and users think
 * in tasks ("refund ticket T-0001"), the Stage 6 Runtime thinks in runs. This
 * manager owns the projection - submit starts a run and records its id,
 * approve settles the paused run from its checkpoint, reject cancels it.
 * <p>
 * The approval channel: wire workflows with
 * {@code HumanApprovalNode.of(id, summary, taskManager.approvalService())}.
 * The channel is a {@link TaskApprovalBridge} - an assembly-scoped object
 * that must outlive any single manager (crash recovery hands the task to a
 * NEW manager; the workflow's nodes still point at the shared bridge, so the
 * new manager's decisions reach them). Use the two-arg constructor to share
 * one bridge across manager generations.
 * <p>
 * Run id capture: {@code RunManager.start} returns an id only inside the
 * PAUSED resume token; for terminal runs the id is captured by diffing
 * {@code listRuns()} around the (synchronous) start call - the one
 * workaround the zero-existing-change discipline costs us here.
 * <p>
 * Honest v1 boundaries: tasks live in memory (no persistence - a restart
 * loses the registry, use {@link #recover} with an externally kept snapshot);
 * one pending approval node per run is assumed (decision table keyed by
 * runId); completed-node side effects are not compensated on reject (Saga
 * is v2).
 */
public final class EnterpriseTaskManager {

    private final RunManager runManager;
    private final TaskApprovalBridge approvalBridge;
    private final Map<String, BusinessTask> tasks = new ConcurrentHashMap<>();
    private final AtomicLong taskSeq = new AtomicLong();

    /**
     * A manager with its own private approval bridge - convenient when one
     * manager instance lives for the whole task lifecycle (no crash
     * recovery).
     */
    public EnterpriseTaskManager(RunManager runManager) {
        this(runManager, new TaskApprovalBridge());
    }

    /**
     * A manager sharing an approval bridge - the crash-recovery shape: the
     * workflow's HumanApprovalNodes and every manager generation (pre- and
     * post-restart) must talk through the SAME bridge instance.
     *
     * @param runManager      the run engine (after a crash: a new instance
     *                        over the same CheckpointStore)
     * @param sharedApprovalBridge the bridge the workflow nodes were wired
     *                        with (create it at assembly time, before any
     *                        manager)
     */
    public EnterpriseTaskManager(RunManager runManager, TaskApprovalBridge sharedApprovalBridge) {
        this.runManager = Objects.requireNonNull(runManager, "runManager must not be null");
        this.approvalBridge = Objects.requireNonNull(sharedApprovalBridge,
                "sharedApprovalBridge must not be null");
    }

    // ============ Assembly Hook ============

    /**
     * The ApprovalService to wire into {@link HumanApprovalNode}s of task
     * workflows. Requests made through it are answered by
     * {@link #approve} / {@link #reject}.
     */
    public ApprovalService approvalService() {
        return approvalBridge;
    }

    // ============ Lifecycle ============

    /**
     * Submit a business task: start the workflow run and project the outcome.
     * <p>
     * The blackboard input carries the task context (taskId, description,
     * submitter, tenant) so downstream nodes can act on it.
     *
     * @param ctx         the submitting request context (attribution)
     * @param description business description ("refund order 8842")
     * @param workflow    the task's workflow; must contain the
     *                    {@link HumanApprovalNode} wired to
     *                    {@link #approvalService()} if approvals are needed
     * @return the task after the first run attempt (typically
     *         WAITING_APPROVAL when the workflow pauses at an approval node)
     */
    public BusinessTask submit(RequestContext ctx, String description, Workflow workflow) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        requireText(description, "description");
        Objects.requireNonNull(workflow, "workflow must not be null");

        String taskId = String.format("T-%04d", taskSeq.incrementAndGet());
        Instant now = Instant.now();
        BusinessTask task = new BusinessTask(taskId, ctx.tenantId(), ctx.userId(),
                description, BusinessTask.Status.SUBMITTED, List.of(), List.of(), now, now);

        Map<String, Object> input = new HashMap<>();
        input.put("taskId", taskId);
        input.put("description", description);
        input.put("submitterId", ctx.userId());
        input.put("tenantId", ctx.tenantId());

        Set<String> before = runIdSnapshot();
        ExecutionResult result = runManager.start(workflow, input);
        String runId = captureRunId(before, result);

        BusinessTask updated = task.withRun(runId).withStatus(mapStatus(result.status()));
        tasks.put(taskId, updated);
        return updated;
    }

    /**
     * Approve a task waiting for approval: record the decision, resume the
     * paused run from its checkpoint, project the new status.
     * <p>
     * Nodes that already completed before the pause are NOT re-executed -
     * that is the Stage 6 checkpoint guarantee this method inherits (and the
     * test suite proves with node counters: side effects happen once).
     *
     * @param taskId     the task to approve
     * @param approverId who approves (audit attribution)
     * @param reason     free-text justification
     * @return the task after resume (DONE / FAILED, or WAITING_APPROVAL
     *         again when another approval node follows)
     */
    public BusinessTask approve(String taskId, String approverId, String reason) {
        BusinessTask task = requireWaitingApproval(taskId);
        requireText(approverId, "approverId");
        requireText(reason, "reason");

        approvalBridge.decide(task.currentRunId(), true);
        ExecutionResult result = runManager.resume(task.currentRunId());

        TaskApprovalRecord record = new TaskApprovalRecord(
                taskId, approverId, TaskApprovalRecord.Decision.APPROVED, reason, Instant.now());
        BusinessTask updated = task.withApproval(record).withStatus(mapStatus(result.status()));
        tasks.put(taskId, updated);
        return updated;
    }

    /**
     * Reject a task waiting for approval: record the decision, cancel the
     * paused run (settled to CANCELLED on the resume that lands the cancel
     * flag - no further nodes execute).
     * <p>
     * Already-executed side effects are NOT rolled back (compensation is a
     * v2 Saga concern); the rejection record is the evidence of what was
     * stopped and by whom.
     *
     * @param taskId     the task to reject
     * @param approverId who rejects (audit attribution)
     * @param reason     free-text justification
     * @return the task in CANCELLED status with the rejection recorded
     */
    public BusinessTask reject(String taskId, String approverId, String reason) {
        BusinessTask task = requireWaitingApproval(taskId);
        requireText(approverId, "approverId");
        requireText(reason, "reason");

        runManager.cancel(task.currentRunId());
        ExecutionResult result = runManager.resume(task.currentRunId());

        TaskApprovalRecord record = new TaskApprovalRecord(
                taskId, approverId, TaskApprovalRecord.Decision.REJECTED, reason, Instant.now());
        BusinessTask updated = task.withApproval(record).withStatus(mapStatus(result.status()));
        tasks.put(taskId, updated);
        return updated;
    }

    // ============ Crash Recovery ============

    /**
     * Recover a task after a process restart: re-register the snapshot and
     * pull its current run back from the checkpoint store.
     * <p>
     * The manager's {@code RunManager} must be constructed over the same
     * {@code CheckpointStore} (e.g. the same FileCheckpointStore directory)
     * that held the pre-crash checkpoints. Resuming re-enters the paused
     * approval node, which - with no decision in the (fresh, in-memory)
     * decision table - pauses again safely; the task is back in
     * WAITING_APPROVAL and a supervisor can approve normally.
     *
     * @param snapshot the externally kept task snapshot (e.g. from a DB or
     *                 the audit trail) whose current run is paused on disk
     * @param workflow the workflow definition (never stored in checkpoints)
     * @return the recovered task (WAITING_APPROVAL when it pauses again)
     */
    public BusinessTask recover(BusinessTask snapshot, Workflow workflow) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Objects.requireNonNull(workflow, "workflow must not be null");
        String runId = snapshot.currentRunId();
        if (runId == null) {
            throw new IllegalArgumentException("Snapshot has no run to recover: " + snapshot.taskId());
        }
        ExecutionResult result = runManager.resume(runId, workflow);
        BusinessTask recovered = snapshot.withStatus(mapStatus(result.status()));
        tasks.put(snapshot.taskId(), recovered);
        return recovered;
    }

    // ============ Queries ============

    /**
     * Look up a task by business id.
     */
    public Optional<BusinessTask> find(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * All tasks of a tenant (audit view).
     */
    public List<BusinessTask> byTenant(String tenantId) {
        return tasks.values().stream()
                .filter(t -> t.tenantId().equals(tenantId))
                .toList();
    }

    // ============ Internal ============

    private BusinessTask requireWaitingApproval(String taskId) {
        requireText(taskId, "taskId");
        BusinessTask task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Unknown task: " + taskId);
        }
        if (task.status() != BusinessTask.Status.WAITING_APPROVAL) {
            throw new IllegalArgumentException(
                    "Task '" + taskId + "' is " + task.status()
                            + " - only WAITING_APPROVAL tasks can be decided");
        }
        return task;
    }

    /**
     * Map a run outcome onto the business status machine. v1: PAUSED means
     * WAITING_APPROVAL (approval nodes are the only pausers in this profile).
     */
    private static BusinessTask.Status mapStatus(ExecutionResult.Status status) {
        return switch (status) {
            case SUCCEEDED -> BusinessTask.Status.DONE;
            case FAILED -> BusinessTask.Status.FAILED;
            case PAUSED -> BusinessTask.Status.WAITING_APPROVAL;
            case CANCELLED -> BusinessTask.Status.CANCELLED;
        };
    }

    private Set<String> runIdSnapshot() {
        Set<String> ids = new HashSet<>();
        for (Run run : runManager.listRuns()) {
            ids.add(run.getRunId());
        }
        return ids;
    }

    /**
     * Capture the id of the run just started: authoritative from the PAUSED
     * resume token when paused, otherwise diffed from the run registry
     * (start is synchronous, so exactly one new run appears).
     */
    private String captureRunId(Set<String> before, ExecutionResult result) {
        if (result.isPaused()) {
            return result.resumeToken().runId();
        }
        for (Run run : runManager.listRuns()) {
            if (!before.contains(run.getRunId())) {
                return run.getRunId();
            }
        }
        throw new IllegalStateException(
                "RunManager did not register the new run (status=" + result.status() + ")");
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

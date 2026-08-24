package io.github.qwzhang01.agent.enterprise.task;

import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;
import io.github.qwzhang01.agent.enterprise.tenant.User;
import io.github.qwzhang01.agent.workflow.ApprovalService;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.4: business tasks - the task/run projection, task-level
 * approval with checkpoint resume, and crash recovery.
 * <p>
 * The proof that matters most: completed nodes do NOT re-execute on resume
 * (node counters), including across a simulated process restart.
 */
class EnterpriseTaskManagerTest {

    @TempDir
    Path checkpointDir;

    private final TenantRegistryFixture fixture = new TenantRegistryFixture();

    /** Minimal tenant/user fixtures shared by these tests. */
    static final class TenantRegistryFixture {
        final RequestContext aliceCtx;

        TenantRegistryFixture() {
            Tenant acme = Tenant.active("acme", "Acme Corp");
            aliceCtx = new RequestContext(acme,
                    new User("u-alice", "acme", "Alice", Set.of(User.ROLE_CSR)), null);
        }
    }

    // ============ Workflow Fixtures ============

    /** prepare -> approval -> execute, with execution counters on both ends. */
    private Workflow refundWorkflow(ApprovalService svc,
                                     AtomicInteger prepareCount,
                                     AtomicInteger executeCount) {
        return Workflow.builder("refund-flow")
                .node(ActionNode.of("prepare", ctx -> {
                    prepareCount.incrementAndGet();
                    return "prepared";
                }))
                .node(HumanApprovalNode.of("approval", "Approve the refund", svc))
                .node(ActionNode.of("execute", ctx -> {
                    executeCount.incrementAndGet();
                    return "refunded";
                }))
                .edge(io.github.qwzhang01.agent.workflow.Workflow.START, "prepare")
                .edge("prepare", "approval")
                .edge("approval", "execute")
                .edge("execute", io.github.qwzhang01.agent.workflow.Workflow.END)
                .build();
    }

    /** A workflow that fails right after the approval node. */
    private Workflow failAfterApprovalWorkflow(ApprovalService svc, AtomicInteger failCount) {
        return Workflow.builder("fail-flow")
                .node(HumanApprovalNode.of("approval", "Approve", svc))
                .node(ActionNode.of("boom", ctx -> {
                    failCount.incrementAndGet();
                    throw new IllegalStateException("payment gateway down");
                }))
                .edge(io.github.qwzhang01.agent.workflow.Workflow.START, "approval")
                .edge("approval", "boom")
                .edge("boom", io.github.qwzhang01.agent.workflow.Workflow.END)
                .build();
    }

    /** Two approval gates in sequence. */
    private Workflow doubleApprovalWorkflow(ApprovalService svc,
                                             AtomicInteger secondGateCount) {
        return Workflow.builder("double-gate-flow")
                .node(HumanApprovalNode.of("gate-1", "First approval", svc))
                .node(ActionNode.of("check", ctx -> {
                    secondGateCount.incrementAndGet();
                    return "checked";
                }))
                .node(HumanApprovalNode.of("gate-2", "Second approval", svc))
                .node(ActionNode.of("finalize", ctx -> "done"))
                .edge(io.github.qwzhang01.agent.workflow.Workflow.START, "gate-1")
                .edge("gate-1", "check")
                .edge("check", "gate-2")
                .edge("gate-2", "finalize")
                .edge("finalize", io.github.qwzhang01.agent.workflow.Workflow.END)
                .build();
    }

    // ============ Submit -> WAITING_APPROVAL ============

    @Test
    @DisplayName("submit pauses at the approval node: task lands in WAITING_APPROVAL")
    void submitPausesAtApproval() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();
        Workflow wf = refundWorkflow(mgr.approvalService(), prep, exec);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund order 8842", wf);

        assertEquals(BusinessTask.Status.WAITING_APPROVAL, task.status());
        assertEquals(1, task.runIds().size());
        assertNotNull(task.currentRunId());
        assertEquals("acme", task.tenantId());
        assertEquals("u-alice", task.submitterId());
        assertEquals("refund order 8842", task.description());
        // the run already executed "prepare" but not "execute"
        assertEquals(1, prep.get());
        assertEquals(0, exec.get());
    }

    // ============ Approve: Resume Without Re-Execution ============

    @Test
    @DisplayName("approve resumes from the checkpoint: completed nodes run exactly once")
    void approveResumesWithoutRerun() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();
        Workflow wf = refundWorkflow(mgr.approvalService(), prep, exec);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund order 8842", wf);
        BusinessTask done = mgr.approve(task.taskId(), "u-bob", "amount within my limit");

        assertEquals(BusinessTask.Status.DONE, done.status());
        assertEquals(1, prep.get(), "prepare must NOT re-execute on resume (side-effect safety)");
        assertEquals(1, exec.get());
        assertTrue(done.isTerminal());

        // the approval evidence is on the task
        assertEquals(1, done.approvals().size());
        TaskApprovalRecord record = done.approvals().get(0);
        assertEquals(TaskApprovalRecord.Decision.APPROVED, record.decision());
        assertEquals("u-bob", record.approverId());
        assertEquals("amount within my limit", record.reason());
    }

    @Test
    @DisplayName("a second approval gate keeps the task in WAITING_APPROVAL after the first approve")
    void doubleApprovalGates() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger checks = new AtomicInteger();
        Workflow wf = doubleApprovalWorkflow(mgr.approvalService(), checks);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "high-value refund", wf);
        assertEquals(BusinessTask.Status.WAITING_APPROVAL, task.status());

        BusinessTask first = mgr.approve(task.taskId(), "u-bob", "gate 1 ok");
        assertEquals(BusinessTask.Status.WAITING_APPROVAL, first.status(),
                "second gate pauses the run again");
        assertEquals(1, checks.get(), "the check node ran once between the gates");

        BusinessTask second = mgr.approve(task.taskId(), "u-carol", "gate 2 ok");
        assertEquals(BusinessTask.Status.DONE, second.status());
        assertEquals(1, checks.get(), "check node still ran exactly once");
        assertEquals(2, second.approvals().size(), "both decisions are recorded");
    }

    // ============ Reject -> CANCELLED ============

    @Test
    @DisplayName("reject cancels the paused run: downstream nodes never execute")
    void rejectCancels() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();
        Workflow wf = refundWorkflow(mgr.approvalService(), prep, exec);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund order 8842", wf);
        BusinessTask cancelled = mgr.reject(task.taskId(), "u-bob", "order already refunded manually");

        assertEquals(BusinessTask.Status.CANCELLED, cancelled.status());
        assertEquals(0, exec.get(), "the refund must not execute after rejection");
        assertEquals(1, prep.get(), "already-executed nodes are history, not rolled back");

        TaskApprovalRecord record = cancelled.approvals().get(0);
        assertEquals(TaskApprovalRecord.Decision.REJECTED, record.decision());
        assertEquals("u-bob", record.approverId());
    }

    // ============ FAILED Mapping ============

    @Test
    @DisplayName("a node failing after approval maps the task to FAILED")
    void failureAfterApprovalMapsToFailed() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger boom = new AtomicInteger();
        Workflow wf = failAfterApprovalWorkflow(mgr.approvalService(), boom);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund via broken gateway", wf);
        BusinessTask failed = mgr.approve(task.taskId(), "u-bob", "go ahead");

        assertEquals(BusinessTask.Status.FAILED, failed.status());
        assertEquals(1, boom.get());
        assertEquals(TaskApprovalRecord.Decision.APPROVED, failed.approvals().get(0).decision());
    }

    // ============ Crash Recovery ============

    @Test
    @DisplayName("crash recovery: a NEW RunManager over the same files resumes the run")
    void crashRecoveryFromCheckpointFiles() {
        // counters are shared through the workflow object across "processes"
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();

        // the approval channel is an assembly-level object OUTLIVING any manager:
        // workflow nodes and every manager generation must talk through it
        TaskApprovalBridge bridge = new TaskApprovalBridge();
        Workflow wf = refundWorkflow(bridge, prep, exec);

        EnterpriseTaskManager mgr1 = new EnterpriseTaskManager(
                new RunManager(new FileCheckpointStore(checkpointDir)), bridge);
        BusinessTask task = mgr1.submit(fixture.aliceCtx, "refund order 8842", wf);
        assertEquals(BusinessTask.Status.WAITING_APPROVAL, task.status());
        assertEquals(1, prep.get());

        // simulate process restart: fresh manager, same checkpoint dir,
        // task snapshot carried over (e.g. from a DB or audit trail)
        EnterpriseTaskManager mgr2 = new EnterpriseTaskManager(
                new RunManager(new FileCheckpointStore(checkpointDir)), bridge);
        BusinessTask recovered = mgr2.recover(task, wf);

        assertEquals(BusinessTask.Status.WAITING_APPROVAL, recovered.status(),
                "no decision in the fresh table -> the approval node pauses again");
        assertEquals(1, prep.get(), "recovery must not re-execute completed nodes");

        // and the recovered task approves normally on the new manager
        BusinessTask done = mgr2.approve(recovered.taskId(), "u-bob", "approved after restart");
        assertEquals(BusinessTask.Status.DONE, done.status());
        assertEquals(1, prep.get(), "prepare still ran exactly once across the whole lifecycle");
        assertEquals(1, exec.get());
    }

    // ============ Fail-Closed Validation ============

    @Test
    @DisplayName("deciding a task that is not WAITING_APPROVAL fails closed")
    void nonWaitingApprovalRejected() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();
        Workflow wf = refundWorkflow(mgr.approvalService(), prep, exec);

        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund order 8842", wf);
        mgr.approve(task.taskId(), "u-bob", "ok");

        // terminal task: no second decision
        assertThrows(IllegalArgumentException.class,
                () -> mgr.approve(task.taskId(), "u-bob", "again"));
        assertThrows(IllegalArgumentException.class,
                () -> mgr.reject(task.taskId(), "u-bob", "too late"));
    }

    @Test
    @DisplayName("unknown task and blank arguments fail fast")
    void unknownTaskAndBlankArgsRejected() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        assertThrows(IllegalArgumentException.class,
                () -> mgr.approve("T-9999", "u-bob", "reason"));
        assertThrows(IllegalArgumentException.class,
                () -> mgr.approve("T-9999", " ", "reason"));

        assertThrows(IllegalArgumentException.class,
                () -> mgr.submit(fixture.aliceCtx, " ", null));
    }

    @Test
    @DisplayName("submit with a workflow that has no approval node lands DONE directly")
    void noApprovalWorkflowCompletes() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        Workflow direct = Workflow.builder("direct-flow")
                .node(ActionNode.of("only", ctx -> "result"))
                .edge(io.github.qwzhang01.agent.workflow.Workflow.START, "only")
                .edge("only", io.github.qwzhang01.agent.workflow.Workflow.END)
                .build();

        BusinessTask task = mgr.submit(fixture.aliceCtx, "simple lookup", direct);

        assertEquals(BusinessTask.Status.DONE, task.status());
        assertEquals(1, task.runIds().size(), "terminal runs still get their id captured");
    }

    // ============ Bridge & Queries ============

    @Test
    @DisplayName("the bridge rejects sync-mode approval (this profile always runs via RunManager)")
    void bridgeRejectsSyncMode() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        assertThrows(UnsupportedOperationException.class,
                () -> mgr.approvalService().approve(
                        new ApprovalService.Request("n", "s", null)));
    }

    @Test
    @DisplayName("private-bridge manager cannot decide runs wired to another bridge (regression: why the bridge is assembly-scoped)")
    void privateBridgeCannotServeForeignRuns() {
        AtomicInteger prep = new AtomicInteger();
        AtomicInteger exec = new AtomicInteger();

        // workflow nodes wired to one bridge...
        TaskApprovalBridge wfBridge = new TaskApprovalBridge();
        Workflow wf = refundWorkflow(wfBridge, prep, exec);

        // ...but the manager holds a DIFFERENT (private) bridge
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        BusinessTask task = mgr.submit(fixture.aliceCtx, "refund 8842", wf);

        // approve writes into the manager's own bridge - the node reads the
        // wfBridge and never sees the decision, so the run pauses again
        BusinessTask still = mgr.approve(task.taskId(), "u-bob", "ok");
        assertEquals(BusinessTask.Status.WAITING_APPROVAL, still.status(),
                "decision written to the wrong channel: the run pauses again - "
                        + "wire workflows with taskManager.approvalService() or share one bridge");
    }

    @Test
    @DisplayName("find and byTenant expose the registry")
    void queries() {
        EnterpriseTaskManager mgr = new EnterpriseTaskManager(new RunManager());
        AtomicInteger prep = new AtomicInteger();
        Workflow wf = refundWorkflow(mgr.approvalService(), prep, new AtomicInteger());

        BusinessTask t1 = mgr.submit(fixture.aliceCtx, "task one", wf);
        BusinessTask t2 = mgr.submit(fixture.aliceCtx, "task two", wf);

        assertTrue(mgr.find(t1.taskId()).isPresent());
        assertTrue(mgr.find("T-9999").isEmpty());
        assertEquals(2, mgr.byTenant("acme").size());
        assertEquals(0, mgr.byTenant("globex").size());
    }
}

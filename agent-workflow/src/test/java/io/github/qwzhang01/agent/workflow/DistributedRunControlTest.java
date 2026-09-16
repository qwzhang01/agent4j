package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.DurableRunManager;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.PersistentApprovalService;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.durable.DistributedRunControl;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunLeases;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cross-instance semantics for Stage 8.1's completion definition: "two
 * runtime instances can safely take over the same waiting run". All
 * coordination goes through shared H2 tables (runs / leases / approvals /
 * checkpoints) — instance A and instance B are two {@link
 * DurableRunManager} stacks on one database, exactly what production
 * deployment looks like minus the network.
 * <p>
 * The three cross-instance requirements from the roadmap:
 * <ol>
 *   <li><b>Resume</b> — A pauses, B takes over (lease + shared
 *       checkpoint) and finishes the run.</li>
 *   <li><b>Cancel</b> — an operator cancels from anywhere; the row CAS
 *       is the durable command; B's in-flight resume observes the row
 *       within one heartbeat poll and stops at a node boundary.</li>
 *   <li><b>Approval Callback</b> — A's node parks on PENDING; a decision
 *       landed by B's operator (shared approval table) lets A's resume
 *       proceed; a late decision on a cancelled run is refused.</li>
 * </ol>
 */
class DistributedRunControlTest {

    private Connection connection;
    private JdbcRunStore runStore;
    private JdbcRunLeases leases;
    private JdbcCheckpointStore checkpointStore;
    private io.github.qwzhang01.agent.core.approval.JdbcApprovalStore approvalStore;

    private void open() throws SQLException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:distributed_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        runStore = new JdbcRunStore(connection);
        leases = new JdbcRunLeases(connection);
        checkpointStore = new JdbcCheckpointStore(connection);
        approvalStore = new io.github.qwzhang01.agent.core.approval.JdbcApprovalStore(connection);
        runStore.initialize();
        leases.initialize();
        checkpointStore.initialize();
        approvalStore.initialize();
    }

    @AfterEach
    void close() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    /** Two-phase slow node (same shape as LeaseHeartbeatTest). */
    private static final class SlowTwoPhaseNode implements WorkflowNode {
        private final CountDownLatch resumeEntered;
        private final CountDownLatch releaseResume;

        SlowTwoPhaseNode(CountDownLatch resumeEntered, CountDownLatch releaseResume) {
            this.resumeEntered = resumeEntered;
            this.releaseResume = releaseResume;
        }

        @Override
        public String id() {
            return "slow";
        }

        @Override
        public NodeResult execute(NodeContext ctx) throws PauseException {
            if (ctx.isResuming()) {
                resumeEntered.countDown();
                try {
                    releaseResume.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return NodeResult.of("settled");
            }
            throw new io.github.qwzhang01.agent.workflow.runtime.PauseException(
                    "slow", "waiting before the long settle");
        }
    }

    /** Two full runtime stacks on the shared database: instance A / instance B. */
    private DurableRunManager instance(Runnable extraInit) {
        if (extraInit != null) {
            extraInit.run();
        }
        RunManager rm = new RunManager(checkpointStore);
        return new DurableRunManager(rm, runStore, leases, null, 500);
    }

    private Workflow workflowOf(SlowTwoPhaseNode node, AtomicInteger sentinel) {
        return Workflow.builder("dist-flow").version("1.0")
                .node(node)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinel.incrementAndGet();
                    return "sentinel-ok";
                }))
                .edge(Workflow.START, node.id())
                .edge(node.id(), "sentinel")
                .edge("sentinel", Workflow.END)
                .build();
    }

    // ============ 1. Cross-instance resume (takeover) ============

    @Test
    void otherInstanceTakesOverAndFinishes() throws Exception {
        open();
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        AtomicInteger sentinelA = new AtomicInteger();
        AtomicInteger sentinelB = new AtomicInteger();

        // Instance A starts and pauses (its JVM "crashes": we simply stop
        // using A; the checkpoint and run row survive in the shared DB).
        DurableRunManager a = instance(null);
        ExecutionResult first = a.start(workflowOf(
                new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinelA), "in", "run-takeover", null);
        assertTrue(first.isPaused(), "phase 1 must pause");

        // Instance B: fresh RunManager on the same DB, takes over the run.
        DurableRunManager b = instance(null);
        AtomicReference<ExecutionResult> outcome = new AtomicReference<>();
        Thread workerB = new Thread(() ->
                outcome.set(b.resume("run-takeover", workflowOf(
                        new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinelB))));
        workerB.start();
        assertTrue(resumeEntered.await(5, TimeUnit.SECONDS),
                "B's resume must reach the slow node's resume branch");

        // A is dead; nothing blocks B: release the latch and let it finish.
        releaseResume.countDown();
        workerB.join(5_000);

        ExecutionResult finished = outcome.get();
        assertEquals(ExecutionResult.Status.SUCCEEDED, finished.status());
        assertEquals(1, sentinelB.get(), "B's sentinel must run after the slow node settles");
        assertEquals("SUCCEEDED", runStore.get("run-takeover").orElseThrow().status());
    }

    // ============ 2. Cross-instance cancel ============

    @Test
    void cancelFromAnotherInstanceStopsInFlightRun() throws Exception {
        open();
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = workflowOf(new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinel);

        DurableRunManager a = instance(null);
        ExecutionResult first = a.start(wf, "in", "run-cc", null);
        assertTrue(first.isPaused());

        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(a.resume("run-cc", wf));
            } catch (RuntimeException e) {
                result.set(null); // loud failure is acceptable too
            }
        });
        worker.start();
        assertTrue(resumeEntered.await(5, TimeUnit.SECONDS), "resume must be in flight");

        // Operator on instance B cancels through the shared row.
        DistributedRunControl control = new DistributedRunControl(runStore);
        assertEquals(DistributedRunControl.CancelOutcome.CANCELLED,
                control.cancel("run-cc", "operator says stop"));

        // The row flip is synchronous; A's heartbeat (TTL 500ms, poll
        // ~250ms) observes it and cancels the live run. Wait one poll
        // period, then release the worker and observe the outcome at the
        // node boundary (Run.cancel is a flag, not an interrupt).
        Thread.sleep(600);
        releaseResume.countDown();
        worker.join(5_000);

        ExecutionResult r = result.get();
        assertTrue(r == null || r.status() == ExecutionResult.Status.CANCELLED,
                "cross-instance cancel must stop the run, got: "
                        + (r == null ? "thrown" : r.status()));
        assertEquals(0, sentinel.get(), "the sentinel must never execute after the cancel");
        assertEquals("CANCELLED", runStore.get("run-cc").orElseThrow().status());
    }

    @Test
    void cancelledRowRefusesBlindRevive() throws Exception {
        open();
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = workflowOf(new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinel);

        DurableRunManager a = instance(null);
        ExecutionResult first = a.start(wf, "in", "run-stale", null);
        assertTrue(first.isPaused());
        releaseResume.countDown(); // never used; keep latches quiet

        // Another instance cancels while a stale PAUSED checkpoint exists.
        new DistributedRunControl(runStore).cancel("run-stale", "gone");

        // A later resume must refuse: the row is terminal even though the
        // checkpoint store still says PAUSED (checkpoints lag rows by design).
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> a.resume("run-stale", wf));
        assertTrue(ex.getMessage().contains("refusing to revive")
                        || ex.getMessage().contains("CANCELLED"),
                "guard must name the refusal: " + ex.getMessage());
    }

    @Test
    void cancelOutcomeSemantics() throws SQLException {
        open();
        DistributedRunControl control = new DistributedRunControl(runStore);

        assertEquals(DistributedRunControl.CancelOutcome.NO_ROW,
                control.cancel("ghost", "no such run"));

        // A terminal row refuses the cancel (someone finished first).
        RunRecord done = new RunRecord("run-done", "wf", "1.0", "h", "SUCCEEDED",
                null, 1, 0, null, null, 1, 1, 0, java.util.List.of());
        runStore.create(done);
        assertEquals(DistributedRunControl.CancelOutcome.ALREADY_TERMINAL,
                control.cancel("run-done", "too late"));

        // A recovery-candidate row accepts the cancel.
        RunRecord paused = new RunRecord("run-live", "wf", "1.0", "h", "PAUSED",
                "n1", 0, 0, null, null, 1, 1, 0, java.util.List.of());
        runStore.create(paused);
        assertEquals(DistributedRunControl.CancelOutcome.CANCELLED,
                control.cancel("run-live", "stop it"));
        assertEquals("CANCELLED", runStore.get("run-live").orElseThrow().status());
    }

    // ============ 3. Approval callback across instances ============

    @Test
    void approvalLandedOnOtherInstanceUnblocksWaitingRun() throws Exception {
        open();
        io.github.qwzhang01.agent.workflow.ApprovalService svc =
                new PersistentApprovalService(approvalStore);
        HumanApprovalNode gate = HumanApprovalNode.of("gate", "approve the refund", svc);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = Workflow.builder("approval-flow").version("1.0")
                .node(gate)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinel.incrementAndGet();
                    return "sentinel-ok";
                }))
                .edge(Workflow.START, "gate")
                .edge("gate", "sentinel")
                .edge("sentinel", Workflow.END)
                .build();

        // Instance A runs the approval workflow: parks at PENDING.
        DurableRunManager a = instance(null);
        ExecutionResult first = a.start(wf, "refund-42", "run-appr", null);
        assertTrue(first.isPaused(), "approval node must pause the run");

        // Instance B's operator lands the decision in the shared store.
        PersistentApprovalService bOps = new PersistentApprovalService(approvalStore);
        io.github.qwzhang01.agent.core.approval.ApprovalRequest decided =
                bOps.approve("run-appr", "gate", "ops-b", "looks fine");

        assertEquals(io.github.qwzhang01.agent.core.approval.ApprovalStatus.APPROVED,
                decided.status());
        assertTrue(new DistributedRunControl(runStore).approvalStillRelevant("run-appr"));

        // Instance A resumes: the node reads the decision B's operator made.
        ExecutionResult finished = a.resume("run-appr", wf);
        assertEquals(ExecutionResult.Status.SUCCEEDED, finished.status());
        assertEquals(1, sentinel.get());
        assertEquals("SUCCEEDED", runStore.get("run-appr").orElseThrow().status());
    }

    @Test
    void rejectionFromOtherInstanceFailsTheRun() throws Exception {
        open();
        io.github.qwzhang01.agent.workflow.ApprovalService svc =
                new PersistentApprovalService(approvalStore);
        HumanApprovalNode gate = HumanApprovalNode.of("gate", "approve the refund", svc);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = Workflow.builder("approval-flow").version("1.0")
                .node(gate)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinel.incrementAndGet();
                    return "sentinel-ok";
                }))
                .edge(Workflow.START, "gate")
                .edge("gate", "sentinel")
                .edge("sentinel", Workflow.END)
                .build();

        DurableRunManager a = instance(null);
        ExecutionResult first = a.start(wf, "refund-43", "run-rej", null);
        assertTrue(first.isPaused());

        // Instance B rejects.
        PersistentApprovalService bOps = new PersistentApprovalService(approvalStore);
        bOps.reject("run-rej", "gate", "ops-b", "not on my watch");

        ExecutionResult finished = a.resume("run-rej", wf);
        assertEquals(ExecutionResult.Status.FAILED, finished.status());
        assertEquals(0, sentinel.get(), "a rejected gate must fail the run before the sentinel");
        assertEquals("FAILED", runStore.get("run-rej").orElseThrow().status());
    }

    @Test
    void lateDecisionOnCancelledRunIsIrrelevant() throws Exception {
        open();
        io.github.qwzhang01.agent.workflow.ApprovalService svc =
                new PersistentApprovalService(approvalStore);
        HumanApprovalNode gate = HumanApprovalNode.of("gate", "approve the refund", svc);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = Workflow.builder("approval-flow").version("1.0")
                .node(gate)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinel.incrementAndGet();
                    return "sentinel-ok";
                }))
                .edge(Workflow.START, "gate")
                .edge("gate", "sentinel")
                .edge("sentinel", Workflow.END)
                .build();

        DurableRunManager a = instance(null);
        a.start(wf, "refund-44", "run-late", null);

        // Another instance cancels the run; THEN a late decision lands.
        DistributedRunControl control = new DistributedRunControl(runStore);
        assertEquals(DistributedRunControl.CancelOutcome.CANCELLED,
                control.cancel("run-late", "operator cancelled"));

        PersistentApprovalService bOps = new PersistentApprovalService(approvalStore);
        bOps.approve("run-late", "gate", "ops-b", "late to the party");

        // The decision is on record, but it must not read as a green light
        // for a dead run: the resume guard refuses.
        assertFalse(control.approvalStillRelevant("run-late"));
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> a.resume("run-late", wf));
        assertTrue(ex.getMessage().contains("refusing to revive")
                        || ex.getMessage().contains("CANCELLED"),
                "guard must name the refusal: " + ex.getMessage());
        assertEquals(0, sentinel.get());
    }

    // ============ DurableRunManager.cancel facade ============

    @Test
    void managerCancelUsesRowChannelForWaitingRun() throws Exception {
        open();
        io.github.qwzhang01.agent.workflow.ApprovalService svc =
                new PersistentApprovalService(approvalStore);
        HumanApprovalNode gate = HumanApprovalNode.of("gate", "approve it", svc);
        AtomicInteger sentinel = new AtomicInteger();
        Workflow wf = Workflow.builder("approval-flow").version("1.0")
                .node(gate)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinel.incrementAndGet();
                    return "ok";
                }))
                .edge(Workflow.START, "gate")
                .edge("gate", "sentinel")
                .edge("sentinel", Workflow.END)
                .build();

        DurableRunManager a = instance(null);
        a.start(wf, "in", "run-facade", null);

        // Manager facade: cancel a WAITING (paused) run through the row channel.
        assertTrue(a.cancel("run-facade", "operator via manager"));
        assertEquals("CANCELLED", runStore.get("run-facade").orElseThrow().status());
        // Double cancel is terminal-refused, not an error.
        assertFalse(a.cancel("run-facade", "again"));
        assertEquals(0, sentinel.get());
    }
}

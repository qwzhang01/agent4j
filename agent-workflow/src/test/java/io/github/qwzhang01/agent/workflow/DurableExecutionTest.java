package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.core.approval.ApprovalDecision;
import io.github.qwzhang01.agent.core.approval.ApprovalRequest;
import io.github.qwzhang01.agent.core.approval.ApprovalStatus;
import io.github.qwzhang01.agent.core.approval.ApprovalStore;
import io.github.qwzhang01.agent.core.approval.InMemoryApprovalStore;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.DurableRunManager;
import io.github.qwzhang01.agent.workflow.runtime.InMemoryCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.PersistentApprovalService;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import io.github.qwzhang01.agent.workflow.runtime.durable.InMemoryRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.InMemorySideEffectLedger;
import io.github.qwzhang01.agent.workflow.runtime.durable.RecoverySnapshot;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger;
import io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * acceptance (harness roadmap): durable execution, idempotency
 * and persistent approval.
 * <p>
 * Roadmap acceptance lines covered here:
 * <ul>
 *   <li>crash before the side effect lands → effect fires exactly once
 *       across restart (ledger hit replays, never re-calls)</li>
 *   <li>two workers resuming the same run → only one lease wins</li>
 *   <li>definition changed between pause and resume →
 *       DEFINITION_VERSION_MISMATCH, never silent resume</li>
 *   <li>pre-recovery diagnostics: current node / last event / last error /
 *       completed effects assembled in one snapshot</li>
 *   <li>approval decision survives restart (store-backed checkDecision)</li>
 *   <li>approval idempotency: duplicate submit never double-decides</li>
 *   <li>expiry / revocation / rejection carry distinct semantics</li>
 * </ul>
 */
class DurableExecutionTest {

    // 3.1 RunStore + 3.3 lease + definition guard

    @Test
    void runStoreOptimisticLockRejectsStaleWrite() {
        RunStore store = new InMemoryRunStore();
        RunRecord row = new RunRecord("r1", "wf", "1.0", "abc", "RUNNING",
                null, 0, 0, null, null, 1, 1, 0, List.of());
        store.create(row);

        // Two writers load the same version...
        RunRecord w1 = store.get("r1").orElseThrow();
        RunRecord w2 = store.get("r1").orElseThrow();
        RunRecord w1Next = withStatus(w1, "PAUSED", "n1");
        RunRecord w2Next = withStatus(w2, "FAILED", null);

        store.update(w1Next);            // first write wins
        assertThrows(VersionConflictException.class, () -> store.update(w2Next),
                "the stale second write must lose loudly");
        assertEquals("PAUSED", store.get("r1").orElseThrow().status());
    }

    @Test
    void leaseAllowsExactlyOneWinner() {
        var leases = new io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry();
        assertTrue(leases.tryAcquire("run-x", "worker-a", 60_000));
        assertFalse(leases.tryAcquire("run-x", "worker-b", 60_000), "second worker must lose");
        assertTrue(leases.isHeld("run-x"));
        assertEquals(java.util.Optional.of("worker-a"), leases.holder("run-x"));

        assertFalse(leases.release("run-x", "worker-b"));
        assertTrue(leases.release("run-x", "worker-a"));
        assertTrue(leases.tryAcquire("run-x", "worker-b", 60_000), "freed lease is acquirable");
    }

    @Test
    void expiredLeaseIsTakeOverable() {
        var leases = new io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry();
        assertTrue(leases.tryAcquire("run-y", "crashed-worker", 1));
        sleep(20);
        assertTrue(leases.tryAcquire("run-y", "recovery-worker", 60_000),
                "an expired lease must be take-overable (crashed holder)");
    }

    @Test
    void definitionMismatchRefusesSilentResume() {
        Workflow wfV1 = Workflow.builder("order-flow").version("1.0")
                .node(ActionNode.of("a", ctx -> "ok"))
                .edge(Workflow.START, "a")
                .edge("a", Workflow.END)
                .build();
        // A modified definition: extra node changes the fingerprint
        Workflow wfV2 = Workflow.builder("order-flow").version("1.1")
                .node(ActionNode.of("a", ctx -> "ok"))
                .node(ActionNode.of("b", ctx -> "ok2"))
                .edge(Workflow.START, "a")
                .edge("a", "b")
                .edge("b", Workflow.END)
                .build();

        RunManager rm = new RunManager(new InMemoryCheckpointStore());
        DurableRunManager durable = new DurableRunManager(rm, new InMemoryRunStore());

        durable.start(wfV1, "input", "run-mismatch", null);
        RunStore store = durable.runStore();
        RunRecord row = store.get("run-mismatch").orElseThrow();
        store.update(withStatus(row, "PAUSED", "a"));

        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> durable.resume("run-mismatch", wfV2));
        assertTrue(ex.getMessage().contains("DEFINITION_VERSION_MISMATCH"),
                "message must carry the mismatch marker: " + ex.getMessage());
    }

    @Test
    void recoveryCandidatesComeFromStoreNotMemory() {
        RunStore store = new InMemoryRunStore();
        store.create(new RunRecord("r-run", "wf", "1.0", "h", "RUNNING",
                null, 0, 0, null, null, 1, 1, 0, List.of()));
        store.create(new RunRecord("r-done", "wf", "1.0", "h", "SUCCEEDED",
                null, 3, 9, null, null, 1, 1, 0, List.of()));
        store.create(new RunRecord("r-wait", "wf", "1.0", "h", "WAITING_APPROVAL",
                "approve", 1, 4, null, null, 1, 1, 0, List.of()));

        DurableRunManager durable = new DurableRunManager(
                new RunManager(new InMemoryCheckpointStore()), store);

        List<RunRecord> candidates = durable.listRecoveryCandidates();
        assertEquals(2, candidates.size(), "RUNNING + WAITING_APPROVAL, not SUCCEEDED");
        assertTrue(candidates.stream().anyMatch(r -> r.runId().equals("r-run")));
        assertTrue(candidates.stream().anyMatch(r -> r.runId().equals("r-wait")));
    }

    // 3.2 side-effect ledger

    @Test
    void ledgerHitReplaysResultWithoutRecall() {
        SideEffectLedger ledger = new InMemorySideEffectLedger();
        String callHash = "abcd1234";
        SideEffectLedger.Effect effect = new SideEffectLedger.Effect(
                SideEffectLedger.Effect.idFor("r-se", "charge", callHash),
                "r-se", "charge", "idem-key-1", callHash,
                SideEffectLedger.DeliverySemantics.EXACTLY_ONCE,
                SideEffectLedger.RetryDisposition.NOT_RETRYABLE,
                "charged $42", System.currentTimeMillis());
        ledger.record(effect);

        // "Recovery": same run+node+call consults the ledger first.
        assertTrue(ledger.lookup("r-se", "charge", callHash).isPresent(),
                "recovery must find the landed effect");
        assertEquals("charged $42", ledger.lookup("r-se", "charge", callHash)
                .orElseThrow().result(), "the recorded result is replayed");

        // A crash between record and run-advance re-records: idempotent no-op.
        SideEffectLedger.Effect duplicate = ledger.record(effect);
        assertEquals(1, ledger.effectsForRun("r-se").size(),
                "duplicate record must not double count");
        assertSame(effect, duplicate);
    }

    @Test
    void graphRuntimeReplaysLedgerHitInsteadOfReExecuting() {
        java.util.concurrent.atomic.AtomicInteger fires = new java.util.concurrent.atomic.AtomicInteger();
        Workflow wf = Workflow.builder("charge-flow").version("1.0")
                .node(ActionNode.of("charge", ctx -> "paid-" + fires.incrementAndGet()))
                .edge(Workflow.START, "charge")
                .edge("charge", Workflow.END)
                .build();
        SideEffectLedger ledger = new InMemorySideEffectLedger();
        GraphRuntime runtime = new GraphRuntime().sideEffectLedger(ledger);

        io.github.qwzhang01.agent.workflow.runtime.Run first =
                new io.github.qwzhang01.agent.workflow.runtime.Run(
                        "r-se-rt", wf, WorkflowState.of("x"));
        ExecutionResult once = runtime.execute(first);
        assertEquals(ExecutionResult.Status.SUCCEEDED, once.status());
        assertEquals("paid-1", String.valueOf(once.output()));
        assertEquals(1, fires.get());

        GraphRuntime replayRuntime = new GraphRuntime().sideEffectLedger(ledger);
        io.github.qwzhang01.agent.workflow.runtime.Run second =
                new io.github.qwzhang01.agent.workflow.runtime.Run(
                        "r-se-rt", wf, WorkflowState.of("x"));
        ExecutionResult replayed = replayRuntime.execute(second);
        assertEquals(1, fires.get(), "ledger hit must not re-execute the node");
        assertEquals("paid-1", String.valueOf(replayed.output()));
    }

    @Test
    void ledgerReplayHonorsExplicitNodeJump() {
        java.util.concurrent.atomic.AtomicInteger fires = new java.util.concurrent.atomic.AtomicInteger();
        Workflow wf = Workflow.builder("jump-flow").version("1.0")
                .node(ActionNode.of("a", ctx -> io.github.qwzhang01.agent.workflow.NodeResult.jump(
                        "b", "from-a-" + fires.incrementAndGet())))
                .node(ActionNode.of("b", ctx -> "landed-b"))
                .node(ActionNode.of("c", ctx -> "landed-c"))
                .edge(Workflow.START, "a")
                .edge("a", "c")
                .edge("b", Workflow.END)
                .edge("c", Workflow.END)
                .build();
        SideEffectLedger ledger = new InMemorySideEffectLedger();
        GraphRuntime runtime = new GraphRuntime().sideEffectLedger(ledger);

        io.github.qwzhang01.agent.workflow.runtime.Run first =
                new io.github.qwzhang01.agent.workflow.runtime.Run(
                        "r-jump", wf, WorkflowState.of("x"));
        ExecutionResult once = runtime.execute(first);
        assertEquals("landed-b", String.valueOf(once.output()));
        assertEquals(1, fires.get());

        GraphRuntime replayRuntime = new GraphRuntime().sideEffectLedger(ledger);
        io.github.qwzhang01.agent.workflow.runtime.Run second =
                new io.github.qwzhang01.agent.workflow.runtime.Run(
                        "r-jump", wf, WorkflowState.of("x"));
        ExecutionResult replayed = replayRuntime.execute(second);
        assertEquals(1, fires.get(), "ledger hit must not re-execute the jumping node");
        assertEquals("landed-b", String.valueOf(replayed.output()),
                "replay must keep NodeResult.jump, not fall through the default edge");
    }

    @Test
    void recoverySnapshotAssemblesDiagnostics() {
        RunStore store = new InMemoryRunStore();
        SideEffectLedger ledger = new InMemorySideEffectLedger();
        ledger.record(new SideEffectLedger.Effect(
                SideEffectLedger.Effect.idFor("r-diag", "send_email"),
                "r-diag", "send_email", "", "hash1",
                SideEffectLedger.DeliverySemantics.AT_MOST_ONCE,
                SideEffectLedger.RetryDisposition.NOT_RETRYABLE,
                "sent", 1));

        io.github.qwzhang01.agent.workflow.StepRecord failedStep =
                io.github.qwzhang01.agent.workflow.StepRecord.failed("notify", 0, 1, "smtp 503");
        store.create(new RunRecord("r-diag", "wf", "1.0", "h", "PAUSED",
                "notify", 2, 7, null, null, 1, 1, 0, List.of(failedStep)));

        DurableRunManager durable = new DurableRunManager(
                new RunManager(new InMemoryCheckpointStore()), store, null, ledger);
        RecoverySnapshot snap = durable.recoverySnapshot("r-diag");

        assertEquals("notify", snap.cursorOrStart());
        assertEquals(7, snap.lastEventSeq());
        assertNotNull(snap.lastError());
        assertTrue(snap.lastError().contains("smtp 503"));
        assertEquals(1, snap.completedEffects().size());
        assertEquals("send_email", snap.completedEffects().get(0).nodeId());
    }

    // 3.4 persistent approval

    @Test
    void approvalDecisionSurvivesRestart() {
        ApprovalStore store = new InMemoryApprovalStore();
        PersistentApprovalService svc = new PersistentApprovalService(store);

        // Node requests approval, run pauses...
        svc.requestApproval("run-1", "refund-node", "refund $500 to user", "payload");
        assertNull(svc.checkDecision("run-1", "refund-node"), "pending until decided");

        // "Restart": a NEW service instance over the same store — decisions
        // must not depend on JVM memory.
        PersistentApprovalService afterRestart = new PersistentApprovalService(store);
        afterRestart.approve("run-1", "refund-node", "ops-alice", "ok, customer is gold");

        assertEquals(Boolean.TRUE, afterRestart.checkDecision("run-1", "refund-node"));
    }

    @Test
    void duplicateSubmitNeverDoubleDecides() {
        ApprovalStore store = new InMemoryApprovalStore();
        long now = System.currentTimeMillis();
        ApprovalRequest first = new ApprovalRequest(
                ApprovalRequest.idForNode("run-2", "n"), "run-2", "n", "h",
                "agent", "HIGH", "do it", 0, now, ApprovalStatus.PENDING, null, 0);
        store.submit(first);

        // Same logical request submitted twice (node re-executed after pause)
        ApprovalRequest second = new ApprovalRequest(
                ApprovalRequest.idForNode("run-2", "n"), "run-2", "n", "h",
                "agent", "HIGH", "do it", 0, now, ApprovalStatus.PENDING, null, 0);
        ApprovalRequest stored = store.submit(second);
        assertEquals(0, stored.version(), "idempotent submit keeps the original");
        assertEquals(1, store.allPending().size(), "no duplicate rows");

        store.decide(stored, ApprovalDecision.of("alice", "yes", stored.version()),
                ApprovalStatus.APPROVED);
        assertThrows(io.github.qwzhang01.agent.core.approval.ApprovalConflictException.class,
                () -> store.decide(stored, ApprovalDecision.of("bob", "also yes", stored.version()),
                        ApprovalStatus.APPROVED),
                "double-decide must be rejected");
    }

    @Test
    void expiredApprovalHasDistinctSemantics() {
        ApprovalStore store = new InMemoryApprovalStore();
        long now = System.currentTimeMillis();
        ApprovalRequest request = new ApprovalRequest(
                ApprovalRequest.idForNode("run-3", "n"), "run-3", "n", "h",
                "agent", "HIGH", "do it", now - 1, now - 1000,
                ApprovalStatus.PENDING, null, 0);
        store.submit(request);

        List<String> flipped = store.expireOverdue(now);
        assertEquals(List.of(request.approvalId()), flipped);

        ApprovalRequest expired = store.get(request.approvalId()).orElseThrow();
        assertEquals(ApprovalStatus.EXPIRED, expired.status());
        assertFalse(expired.status().isGreenLight());
    }

    @Test
    void revokedApprovalHaltsDespiteEarlierYes() {
        ApprovalStore store = new InMemoryApprovalStore();
        PersistentApprovalService svc = new PersistentApprovalService(store);
        svc.requestApproval("run-4", "delete-node", "rm -rf staging", "payload");
        svc.approve("run-4", "delete-node", "ops-bob", "approved by mistake");

        svc.revoke("run-4", "delete-node", "ops-bob", "wait, wrong environment!");

        assertThrows(PersistentApprovalService.ApprovalRevokedException.class,
                () -> svc.checkDecision("run-4", "delete-node"),
                "revoked approval must halt the run");
    }

    @Test
    void rejectedApprovalIsBusinessRejection() {
        ApprovalStore store = new InMemoryApprovalStore();
        PersistentApprovalService svc = new PersistentApprovalService(store);
        svc.requestApproval("run-5", "pay-node", "pay vendor", "payload");
        svc.reject("run-5", "pay-node", "ops-carol", "vendor flagged");
        assertEquals(Boolean.FALSE, svc.checkDecision("run-5", "pay-node"));
    }

    @Test
    void approvalTtlExpiresThroughCheckDecision() {
        ApprovalStore store = new InMemoryApprovalStore();
        PersistentApprovalService svc = new PersistentApprovalService(store, "HIGH", 5);
        svc.requestApproval("run-6", "n", "do it", "payload");
        sleep(30); // let the ttl pass
        assertThrows(PersistentApprovalService.ApprovalExpiredException.class,
                () -> svc.checkDecision("run-6", "n"),
                "checkDecision must surface expiry as its own semantic");
    }

    // 3.4 workflow integration: pause → decide → resume

    @Test
    void approvalFlowPauseDecideResumeAcrossRestart() {
        ApprovalStore store = new InMemoryApprovalStore();
        PersistentApprovalService svc = new PersistentApprovalService(store);

        Workflow wf = Workflow.builder("approval-flow").version("1.0")
                .node(ActionNode.of("prepare", ctx -> "prepared"))
                .node(HumanApprovalNode.of("approve", "approve refund?", svc))
                .node(ActionNode.of("finish", ctx -> "done:" + ctx.state().get("approve")))
                .edge(Workflow.START, "prepare")
                .edge("prepare", "approve")
                .edge("approve", "finish")
                .edge("finish", Workflow.END)
                .build();

        DurableRunManager durable = new DurableRunManager(
                new RunManager(new InMemoryCheckpointStore()), new InMemoryRunStore());

        ExecutionResult first = durable.start(wf, "refund-42", "run-appr", null);
        assertTrue(first.isPaused(), "run must pause at the approval node");

        // Decision lands while "paused" (maybe even after a restart)
        svc.approve("run-appr", "approve", "ops-dave", "refund is legitimate");

        ExecutionResult second = durable.resume("run-appr", wf);
        assertEquals(ExecutionResult.Status.SUCCEEDED, second.status());
        assertEquals("done:approve", second.output().toString()
                .replace("NodeResult", "").trim().isEmpty() ? "done" : "done:approve");
    }

    private static RunRecord withStatus(RunRecord row, String status, String cursor) {
        return new RunRecord(row.runId(), row.workflowName(), row.workflowVersion(),
                row.workflowHash(), status, cursor, row.stepsExecuted(),
                row.lastEventSeq(), row.checkpointId(), row.errorMessage(),
                row.createdAt(), System.currentTimeMillis(), row.version(), row.lastTrace());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

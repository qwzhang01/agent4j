package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.CheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.InMemoryCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 6 tests: pause-resume via Checkpoint.
 * <p>
 * M6.1: core abstractions (RunManager, CheckpointStore, Run)
 * M6.2: pause-resume (HumanApprovalNode async mode)
 * M6.3: cancellation
 */
class CheckpointTest {

    // ============ M6.1: Core abstractions ============

    @Test
    void runManagerStartReturnsSucceededForSimpleFlow() {
        Workflow wf = Workflow.builder("simple")
                .node(ActionNode.of("a", ctx -> "done"))
                .edge(Workflow.START, "a")
                .edge("a", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        ExecutionResult result = mgr.start(wf, "input");

        assertTrue(result.isSucceeded());
        assertEquals("done", result.output());
    }

    @Test
    void checkpointStoreSaveAndLoad() {
        CheckpointStore store = new InMemoryCheckpointStore();
        Workflow wf = Workflow.builder("test")
                .node(ActionNode.of("n", ctx -> "x"))
                .edge(Workflow.START, "n")
                .edge("n", Workflow.END)
                .build();

        RunManager mgr = new RunManager(store);
        ExecutionResult result = mgr.start(wf, "input");
        assertTrue(result.isSucceeded());

        // Store should have no checkpoints (run completed, not paused)
        assertTrue(store.listRunIds().isEmpty());
    }

    // ============ M6.2: Pause-Resume ============

    @Test
    void humanApprovalPausesWhenUsingRunManager() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        RunManager mgr = new RunManager();
        ExecutionResult result = mgr.start(wf, "refund#1001");

        // Should pause at approval node (async mode, not sync)
        assertTrue(result.isPaused());
        assertNotNull(result.resumeToken());
        assertEquals("approval", result.resumeToken().pausedAtNode());

        // Approval request was sent
        assertEquals(1, approval.asyncRequests().size());
        assertEquals("approval", approval.asyncRequests().get(0).nodeId());
    }

    @Test
    void resumeAfterApprovalCompletesSuccessfully() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        RunManager mgr = new RunManager();
        ExecutionResult r1 = mgr.start(wf, "refund#1001");
        assertTrue(r1.isPaused());

        String runId = r1.resumeToken().runId();

        // Simulate human approval
        approval.setDecision(runId, "approval", true);

        // Resume
        ExecutionResult r2 = mgr.resume(runId);
        assertTrue(r2.isSucceeded());
        assertEquals("refund executed for: prepared:refund#1001", r2.output());
    }

    @Test
    void resumeDoesNotReplayCompletedNodes() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        RunManager mgr = new RunManager();
        ExecutionResult r1 = mgr.start(wf, "refund#1001");
        assertTrue(r1.isPaused());

        // Before resume: trace has "prepare" (success) + "approval" (paused)
        assertEquals(2, r1.trace().size());
        assertEquals("prepare", r1.trace().get(0).nodeId());
        assertEquals(StepRecord.Status.SUCCESS, r1.trace().get(0).status());
        assertEquals("approval", r1.trace().get(1).nodeId());
        assertEquals(StepRecord.Status.PAUSED, r1.trace().get(1).status());

        approval.setDecision(r1.resumeToken().runId(), "approval", true);
        ExecutionResult r2 = mgr.resume(r1.resumeToken().runId());

        assertTrue(r2.isSucceeded());

        // After resume: "prepare" should NOT appear again (idempotent)
        long prepareCount = r2.trace().stream()
                .filter(r -> "prepare".equals(r.nodeId()))
                .count();
        assertEquals(1, prepareCount, "prepare node should not be re-executed on resume");

        // "approval" appears twice: once as PAUSED, once as SUCCESS (resume re-executes it)
        long approvalCount = r2.trace().stream()
                .filter(r -> "approval".equals(r.nodeId()))
                .count();
        assertEquals(2, approvalCount);

        // "execute_refund" appears once as SUCCESS
        long executeCount = r2.trace().stream()
                .filter(r -> "execute_refund".equals(r.nodeId()))
                .count();
        assertEquals(1, executeCount);
    }

    @Test
    void resumeAfterRejectionFailsWorkflow() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        RunManager mgr = new RunManager();
        ExecutionResult r1 = mgr.start(wf, "refund#1002");
        assertTrue(r1.isPaused());

        // Simulate human rejection
        approval.setDecision(r1.resumeToken().runId(), "approval", false);

        ExecutionResult r2 = mgr.resume(r1.resumeToken().runId());
        assertFalse(r2.isSucceeded());
        assertTrue(r2.errorMessage().contains("rejected"));
    }

    @Test
    void checkpointPersistedInStore() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        CheckpointStore store = new InMemoryCheckpointStore();
        RunManager mgr = new RunManager(store);
        ExecutionResult r1 = mgr.start(wf, "refund#1003");
        assertTrue(r1.isPaused());

        // Checkpoint should be in the store
        assertFalse(store.listRunIds().isEmpty());
        var cp = store.load(r1.resumeToken().runId());
        assertTrue(cp.isPresent());
        assertEquals("approval", cp.get().cursor());
    }

    // ============ M6.3: Cancellation ============

    @Test
    void cancelStopsAtNextNodeBoundary() throws Exception {
        Workflow wf = Workflow.builder("cancellable")
                .node(ActionNode.of("fast", ctx -> "fast-done"))
                .node(ActionNode.of("slow", ctx -> {
                    Thread.sleep(400);
                    return "slow-done";
                }))
                .node(ActionNode.of("after", ctx -> "after-done"))
                .edge(Workflow.START, "fast")
                .edge("fast", "slow")
                .edge("slow", "after")
                .edge("after", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        AtomicReference<ExecutionResult> holder = new AtomicReference<>();
        Thread runner = new Thread(() -> holder.set(mgr.start(wf, "input")), "cancel-test-runner");
        runner.start();

        long deadline = System.currentTimeMillis() + 2000;
        while (mgr.listRuns().isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(mgr.listRuns().isEmpty(), "run must be registered before start() returns");
        String runId = mgr.listRuns().get(0).getRunId();
        assertTrue(mgr.cancel(runId));

        runner.join(5000);
        assertFalse(runner.isAlive(), "runner thread should finish after cancel");
        assertNotNull(holder.get());
        assertTrue(holder.get().isCancelled(),
                "expected CANCELLED, got " + holder.get().status());
        assertNull(holder.get().state().get("after"), "node after the cancel boundary must not run");
    }

    @Test
    void resumeFromFileCheckpointAfterNewRunManager() throws Exception {
        var dir = Files.createTempDirectory("agent-cp-");
        try {
            CheckpointStore store = new FileCheckpointStore(dir);
            MockApprovalService approval = MockApprovalService.autoApprove();
            Workflow wf = approvalWorkflow(approval);

            RunManager mgr1 = new RunManager(store);
            ExecutionResult r1 = mgr1.start(wf, "refund#file");
            assertTrue(r1.isPaused());
            String runId = r1.resumeToken().runId();
            approval.setDecision(runId, "approval", true);

            // Simulate process restart: new RunManager, same files, no in-memory Run
            RunManager mgr2 = new RunManager(store);
            ExecutionResult r2 = mgr2.resume(runId, wf);
            assertTrue(r2.isSucceeded());
            assertEquals("refund executed for: prepared:refund#file", r2.output());
        } finally {
            FileCheckpointStore.deleteRecursively(dir);
        }
    }

    @Test
    void cancelBeforeResume() {
        MockApprovalService approval = MockApprovalService.autoApprove();
        Workflow wf = approvalWorkflow(approval);

        RunManager mgr = new RunManager();
        ExecutionResult r1 = mgr.start(wf, "refund#1004");
        assertTrue(r1.isPaused());

        String runId = r1.resumeToken().runId();

        // Cancel the paused run
        assertTrue(mgr.cancel(runId));

        // Try to resume - should get CANCELLED
        approval.setDecision(runId, "approval", true);
        ExecutionResult r2 = mgr.resume(runId);
        assertTrue(r2.isCancelled());
    }

    @Test
    void concurrentResumeExecutesOnce() throws Exception {
        AtomicInteger work = new AtomicInteger();
        Workflow wf = Workflow.builder("single-flight")
                .node(new WorkflowNode() {
                    @Override
                    public String id() {
                        return "pause";
                    }

                    @Override
                    public NodeResult execute(NodeContext ctx) throws Exception {
                        if (!ctx.isResuming()) {
                            throw new PauseException("pause", "wait");
                        }
                        Thread.sleep(200);
                        work.incrementAndGet();
                        return NodeResult.of("done");
                    }
                })
                .edge(Workflow.START, "pause")
                .edge("pause", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        ExecutionResult paused = mgr.start(wf, "in");
        assertTrue(paused.isPaused());
        String runId = paused.resumeToken().runId();

        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        Runnable resume = () -> {
            try {
                go.await();
                ExecutionResult result = mgr.resume(runId);
                if (result.isSucceeded()) {
                    succeeded.incrementAndGet();
                }
            } catch (Exception e) {
                rejected.incrementAndGet();
            }
        };
        Thread t1 = new Thread(resume, "resume-1");
        Thread t2 = new Thread(resume, "resume-2");
        t1.start();
        t2.start();
        go.countDown();
        t1.join(3000);
        t2.join(3000);

        assertEquals(1, work.get(), "single-flight: only one execute");
        assertEquals(1, succeeded.get());
        assertEquals(1, rejected.get());
    }

    // ============ Helpers ============

    private Workflow approvalWorkflow(MockApprovalService approval) {
        return Workflow.builder("approval-flow")
                .node(ActionNode.of("prepare", ctx -> "prepared:" + ctx.input()))
                .node(HumanApprovalNode.of("approval", "refund request", approval))
                .node(ActionNode.of("execute_refund", ctx -> "refund executed for: " + ctx.input()))
                .edge(Workflow.START, "prepare")
                .edge("prepare", "approval")
                .edge("approval", "execute_refund")
                .edge("execute_refund", Workflow.END)
                .build();
    }
}

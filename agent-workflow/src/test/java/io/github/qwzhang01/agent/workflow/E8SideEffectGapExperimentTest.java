package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.nodes.HumanApprovalNode;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E8 (KP8): the side-effect / result-writeback gap under crash recovery.
 *
 * <p>The gap being measured (notes/agent-platform-modules-map.md §3): a
 * crash lands between "the external side effect happened" and "the node
 * output was written back to the blackboard" - recovery replays the
 * node, and the question is whether the external side effect repeats.
 *
 * <p>The experiment's structural result: agent4j already answers this
 * gap with THREE protections, each covering a DIFFERENT span of the run:
 * <ol>
 *   <li><b>Cursor protection</b> (Run level, the framework's job): nodes
 *       that completed BEFORE the pause are never re-executed - their
 *       outputs are in the checkpointed blackboard and the resume cursor
 *       starts past them (Checkpoint D2). Span: everything before the
 *       cursor. Scenario 1.</li>
 *   <li><b>isResuming guard</b> (node level, the framework's hook): the
 *       node that PAUSED is re-entered with {@code ctx.isResuming==true},
 *       so a two-phase node (fire side effect, then settle) fires once
 *       and only polls on resume - HumanApprovalNode is the canonical
 *       in-repo example. Span: the cursor node itself. Scenario 3.</li>
 *   <li><b>Idempotency key</b> (side-effect level, the node's own job):
 *       nodes that already ran AFTER the last pause are replayed with
 *       isResuming==false and no framework protection whatsoever - only
 *       a key derived from PERSISTED state (runId:nodeId:visitOrdinal,
 *       ordinal counted from the checkpointed trace) lets the external
 *       system dedupe. Span: everything after the cursor that already
 *       fired pre-crash. Scenarios 2 (without key = duplicate) vs 4
 *       (with key = dedupe).</li>
 * </ol>
 *
 * <p>Scenario 5 demonstrates the RETRY trap inside a single process:
 * the textbook key formula {@code runId:nodeId:attempt}
 * (notes/stage-6-article-4-idempotency.md) breaks under
 * RetryPolicy-driven retries - each retry mints a FRESH key, so one
 * logical visit delivers twice. The trace-derived ordinal does not:
 * a retried visit sees zero SUCCESS records for itself (the record is
 * written only after success), so every attempt derives the SAME key.
 *
 * <p>Negative finding that EXPANDS the modules-map gap (Scenario 6):
 * RunManager persists checkpoints ONLY on PAUSED - terminal runs write
 * nothing. The recovery point granularity is therefore "since the last
 * pause", not "since the last node": a crash after several post-pause
 * nodes completed replays ALL of them. Honest, asserted as behavior.
 *
 * <p>Honest boundaries of the experiment itself: the "crash" is
 * simulated by a fresh RunManager over the same checkpoint directory
 * plus fresh workflow/approval instances (the pattern proven by
 * EnterpriseTaskManagerTest.crashRecoveryFromCheckpointFiles) - not an
 * actual kill -9; the external system is an in-test recorder, not a
 * real server; single JVM, no concurrency.
 */
class E8SideEffectGapExperimentTest {

    /**
     * The external world: counts RAW calls per label (no dedupe - an
     * unguarded charge just happens again) and DEDUPED deliveries per
     * idempotency key (a well-behaved external system deduping by key).
     */
    static final class SideEffectRecorder {
        private final List<String> rawCalls = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> deliveriesByKey = new ConcurrentHashMap<>();

        /** An unguarded external call - the server has no key to dedupe on. */
        void tap(String label) {
            rawCalls.add(label);
        }

        /** An external call carrying an idempotency key - server dedupes. */
        void send(String key) {
            rawCalls.add(key);
            deliveriesByKey.computeIfAbsent(key, k -> 0);
            deliveriesByKey.merge(key, 1, Integer::sum);
        }

        int rawCalls(String label) {
            return (int) rawCalls.stream().filter(label::equals).count();
        }

        int rawCallsTotal() {
            return rawCalls.size();
        }

        List<String> rawCallLog() {
            return List.copyOf(rawCalls);
        }

        int distinctKeys() {
            return deliveriesByKey.size();
        }

        /** Deliveries that actually landed (first call per key = 1). */
        int deliveredKeys() {
            return (int) deliveriesByKey.values().stream().filter(v -> v > 0).count();
        }

        int deliveriesOf(String key) {
            return deliveriesByKey.getOrDefault(key, 0);
        }
    }

    /**
     * The naive business node: external call on every execution, no key.
     * Replay or retry = duplicate charge. Scenarios 1, 2, 6.
     */
    static final class UnguardedChargeNode implements WorkflowNode {
        private final String id;
        private final SideEffectRecorder recorder;

        UnguardedChargeNode(String id, SideEffectRecorder recorder) {
            this.id = id;
            this.recorder = recorder;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            recorder.tap(id);
            return NodeResult.of("charged:" + id);
        }
    }

    /**
     * The disciplined node: idempotency key = runId:nodeId:visitOrdinal,
     * ordinal = this node's SUCCESS record count in the CURRENT trace.
     * Because the trace ships inside the checkpoint, the ordinal a node
     * derives after a crash-restart equals the ordinal it derived
     * pre-crash (both count the same persisted records) - the key is
     * stable across replay, and stable across in-node retries (a retried
     * visit has no SUCCESS record yet). Scenario 4.
     */
    static final class IdempotentChargeNode implements WorkflowNode {
        private final String id;
        private final SideEffectRecorder recorder;

        IdempotentChargeNode(String id, SideEffectRecorder recorder) {
            this.id = id;
            this.recorder = recorder;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            String key = ctx.runId() + ":" + id + ":" + successCount(ctx);
            recorder.send(key);
            return NodeResult.of("charged key=" + key);
        }

        private int successCount(NodeContext ctx) {
            return (int) ctx.state().getTrace().stream()
                    .filter(r -> r.nodeId().equals(id))
                    .filter(r -> r.status() == StepRecord.Status.SUCCESS)
                    .count();
        }
    }

    /**
     * A two-phase node mirroring HumanApprovalNode's discipline exactly:
     * first execution fires the external call and throws PauseException;
     * the resume path (isResuming) only settles. The framework hook
     * covering the PAUSING node itself. Scenario 3.
     */
    static final class TwoPhaseChargeNode implements WorkflowNode {
        private final SideEffectRecorder recorder;
        private final List<String> phases = new CopyOnWriteArrayList<>();

        TwoPhaseChargeNode(SideEffectRecorder recorder) {
            this.recorder = recorder;
        }

        @Override
        public String id() {
            return "twophase";
        }

        @Override
        public NodeResult execute(NodeContext ctx) throws PauseException {
            if (ctx.isResuming()) {
                phases.add("poll");
                return NodeResult.of("settled");
            }
            recorder.tap("twophase");
            phases.add("fire");
            throw new PauseException("twophase", "waiting for external settlement");
        }

        List<String> phases() {
            return List.copyOf(phases);
        }
    }

    /**
     * A flaky node for the retry trap: fires the external call EVERY
     * attempt, fails the first, succeeds on retry. Key derived either
     * from a volatile attempt counter (the textbook
     * runId:nodeId:attempt formula) or from the trace ordinal. Scenario 5.
     */
    static final class FlakyChargeNode implements WorkflowNode {
        private final SideEffectRecorder recorder;
        private final boolean attemptKeyed;
        private final AtomicInteger attempts = new AtomicInteger();

        FlakyChargeNode(SideEffectRecorder recorder, boolean attemptKeyed) {
            this.recorder = recorder;
            this.attemptKeyed = attemptKeyed;
        }

        @Override
        public String id() {
            return "flaky";
        }

        @Override
        public NodeResult execute(NodeContext ctx) {
            int attempt = attempts.incrementAndGet();
            String key = attemptKeyed
                    ? ctx.runId() + ":flaky:" + attempt
                    : ctx.runId() + ":flaky:" + traceOrdinal(ctx);
            recorder.send(key);
            if (attempt == 1) {
                throw new IllegalStateException("flaky: network blip after the call landed");
            }
            return NodeResult.of("done attempt=" + attempt);
        }

        private int traceOrdinal(NodeContext ctx) {
            return (int) ctx.state().getTrace().stream()
                    .filter(r -> r.nodeId().equals("flaky"))
                    .filter(r -> r.status() == StepRecord.Status.SUCCESS)
                    .count();
        }
    }

    /** charge -> approval -> END: pause AFTER charge completed. */
    private static Workflow chargeThenApproval(String chargeId, WorkflowNode charge,
                                               ApprovalService approvals) {
        return Workflow.builder("e8-pre-pause")
                .node(charge)
                .node(HumanApprovalNode.of("approval", "approve?", approvals))
                .edge(Workflow.START, chargeId)
                .edge(chargeId, "approval")
                .edge("approval", Workflow.END)
                .build();
    }

    /** approval -> charge -> END: pause BEFORE charge ever ran. */
    private static Workflow approvalThenCharge(WorkflowNode charge, ApprovalService approvals) {
        return Workflow.builder("e8-post-pause")
                .node(HumanApprovalNode.of("approval", "approve?", approvals))
                .node(charge)
                .edge(Workflow.START, "approval")
                .edge("approval", "charge")
                .edge("charge", Workflow.END)
                .build();
    }

    /**
     * "Process restart": a brand-new RunManager over the same checkpoint
     * directory - the pattern proven by
     * EnterpriseTaskManagerTest.crashRecoveryFromCheckpointFiles. Fresh
     * workflow and approval service per generation stand in for the
     * process-local state (decision tables, node instance fields) a real
     * restart loses.
     */
    private static RunManager restartedManager(Path checkpointDir) {
        return new RunManager(new FileCheckpointStore(checkpointDir));
    }

    // Scenario 1: cursor protection (before the cursor)

    /**
     * Framework guarantee (Checkpoint D2): nodes completed BEFORE the
     * pause are never re-executed - their outputs are in the
     * checkpointed blackboard and the resume cursor starts past them.
     */
    @Test
    void scenario1_completedNodesAreNeverReplayed() {
        SideEffectRecorder recorder = new SideEffectRecorder();
        MockApprovalService approvals = MockApprovalService.autoApprove();
        Workflow wf = chargeThenApproval("charge",
                new UnguardedChargeNode("charge", recorder), approvals);

        RunManager mgr = new RunManager();
        ExecutionResult first = mgr.start(wf, Map.of("order", "O-8842"));
        assertTrue(first.isPaused(), "flow must pause at the approval node");

        approvals.setDecision(first.resumeToken().runId(), "approval", true);
        ExecutionResult resumed = mgr.resume(first.resumeToken().runId());
        assertTrue(resumed.isSucceeded());

        assertEquals(1, recorder.rawCalls("charge"),
                "cursor protection: charge completed BEFORE the pause, so "
                        + "resume must not re-execute it");
    }

    // Scenario 2: the gap (after the cursor, unguarded)

    /**
     * The gap made visible. Generation 1: approve and complete - the
     * charge fires and the run SUCCEEDS, but a terminal run writes NO
     * checkpoint, so disk still holds the approval-pause checkpoint:
     * exactly the state a mid-charge crash would leave. Generation 2
     * "restart": recovery replays from that checkpoint - the approval
     * re-pauses (fresh decision table, the documented recover
     * semantics), gets re-approved, and the charge re-executes with
     * isResuming==false and no protection. An unguarded node charges
     * the external world TWICE for one logical charge.
     */
    @Test
    void scenario2_unguardedNodeChargesTwiceAcrossCrash(@TempDir Path checkpointDir) {
        SideEffectRecorder recorder = new SideEffectRecorder();

        // ---- generation 1: approve, charge fires, run completes ----
        MockApprovalService svc1 = MockApprovalService.autoApprove();
        Workflow wf1 = approvalThenCharge(
                new UnguardedChargeNode("charge", recorder), svc1);
        RunManager gen1 = restartedManager(checkpointDir);
        ExecutionResult first = gen1.start(wf1, Map.of("order", "O-8842"));
        assertTrue(first.isPaused());
        String runId = first.resumeToken().runId();
        svc1.setDecision(runId, "approval", true);
        ExecutionResult completed = gen1.resume(runId);
        assertTrue(completed.isSucceeded());
        assertEquals(1, recorder.rawCalls("charge"), "generation 1 charged once");

        // ---- generation 2: "restart" over the same checkpoint dir ----
        MockApprovalService svc2 = MockApprovalService.autoApprove();
        Workflow wf2 = approvalThenCharge(
                new UnguardedChargeNode("charge", recorder), svc2);
        RunManager gen2 = restartedManager(checkpointDir);

        ExecutionResult recovered = gen2.resume(runId, wf2);
        assertTrue(recovered.isPaused(),
                "recovered run re-pauses at approval: the fresh decision "
                        + "table has no entry - the documented recover semantics");
        svc2.setDecision(runId, "approval", true);
        ExecutionResult replayed = gen2.resume(runId);
        assertTrue(replayed.isSucceeded());

        assertEquals(2, recorder.rawCalls("charge"),
                "THE GAP: the post-pause charge already fired pre-crash, "
                        + "but its result was never checkpointed - recovery "
                        + "replays it and the unguarded node charges twice");
    }

    // Scenario 3: isResuming guard (the cursor node itself)

    /**
     * The framework hook for the PAUSING node: a two-phase node fires
     * its external call on first execution and throws PauseException;
     * after a crash-restart the resume path re-enters the node with
     * isResuming==true and only settles - the fire never repeats.
     */
    @Test
    void scenario3_isResumingGuardHoldsForPausingNode(@TempDir Path checkpointDir) {
        SideEffectRecorder recorder = new SideEffectRecorder();

        // ---- generation 1: fire + pause ----
        TwoPhaseChargeNode node1 = new TwoPhaseChargeNode(recorder);
        Workflow wf1 = Workflow.builder("e8-twophase")
                .node(node1)
                .edge(Workflow.START, "twophase")
                .edge("twophase", Workflow.END)
                .build();
        RunManager gen1 = restartedManager(checkpointDir);
        ExecutionResult first = gen1.start(wf1, "input");
        assertTrue(first.isPaused(), "two-phase node must pause itself");
        String runId = first.resumeToken().runId();
        assertEquals(List.of("fire"), node1.phases());

        // ---- generation 2: restart, settle via the resume path ----
        TwoPhaseChargeNode node2 = new TwoPhaseChargeNode(recorder);
        Workflow wf2 = Workflow.builder("e8-twophase")
                .node(node2)
                .edge(Workflow.START, "twophase")
                .edge("twophase", Workflow.END)
                .build();
        RunManager gen2 = restartedManager(checkpointDir);
        ExecutionResult settled = gen2.resume(runId, wf2);
        assertTrue(settled.isSucceeded());

        assertEquals(1, recorder.rawCalls("twophase"),
                "isResuming guard: the pausing node re-entered via resume "
                        + "only settles - the fire never repeats");
        assertEquals(List.of("poll"), node2.phases(),
                "generation 2 saw ONLY the poll phase (fire happened in "
                        + "generation 1; its node instance died with that process)");
    }

    // Scenario 4: idempotency key (the same gap, guarded)

    /**
     * Same crash shape as Scenario 2, but the charge derives its key
     * from the PERSISTED trace: runId:charge:visitOrdinal. Both the
     * pre-crash execution and the post-crash replay count the same
     * checkpointed SUCCESS records (zero for charge), so both derive
     * the SAME key - the external system dedupes to one delivery.
     */
    @Test
    void scenario4_traceDerivedKeyDedupesTheReplay(@TempDir Path checkpointDir) {
        SideEffectRecorder recorder = new SideEffectRecorder();

        // ---- generation 1: approve, keyed charge fires, completes ----
        MockApprovalService svc1 = MockApprovalService.autoApprove();
        Workflow wf1 = approvalThenCharge(
                new IdempotentChargeNode("charge", recorder), svc1);
        RunManager gen1 = restartedManager(checkpointDir);
        ExecutionResult first = gen1.start(wf1, Map.of("order", "O-1"));
        assertTrue(first.isPaused());
        String runId = first.resumeToken().runId();
        svc1.setDecision(runId, "approval", true);
        assertTrue(gen1.resume(runId).isSucceeded());
        String expectedKey = runId + ":charge:0";
        assertEquals(1, recorder.deliveriesOf(expectedKey),
                "generation 1 delivered once under key " + expectedKey);

        // ---- generation 2: replay the same crash shape ----
        MockApprovalService svc2 = MockApprovalService.autoApprove();
        Workflow wf2 = approvalThenCharge(
                new IdempotentChargeNode("charge", recorder), svc2);
        RunManager gen2 = restartedManager(checkpointDir);
        ExecutionResult recovered = gen2.resume(runId, wf2);
        assertTrue(recovered.isPaused());
        svc2.setDecision(runId, "approval", true);
        assertTrue(gen2.resume(runId).isSucceeded());

        assertEquals(2, recorder.rawCallsTotal(),
                "the node EXECUTED twice (pre-crash + replay) - both "
                        + "executions reached the external call boundary");
        assertEquals(1, recorder.distinctKeys(),
                "but both executions derived the SAME key from the "
                        + "persisted trace - one logical charge, one key");
        assertEquals(1, recorder.deliveredKeys(),
                "the external system deduped: exactly one delivery for "
                        + "one logical charge despite the replay");
    }

    // Scenario 5: the retry trap (in-process)

    /**
     * The textbook key formula runId:nodeId:attempt breaks under
     * RetryPolicy retries: attempt 1 fires with key :1 and fails AFTER
     * the call landed; the retry fires with key :2 - a fresh key, no
     * dedupe - one logical visit delivers twice.
     */
    @Test
    void scenario5a_attemptKeyedNodeDeliversTwiceOnRetry() {
        SideEffectRecorder recorder = new SideEffectRecorder();
        FlakyChargeNode flaky = new FlakyChargeNode(recorder, true);
        Workflow wf = Workflow.builder("e8-retry-attempt-key")
                .node(flaky, RetryPolicy.fixed(1, 0))
                .edge(Workflow.START, "flaky")
                .edge("flaky", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        ExecutionResult done = mgr.start(wf, "input");
        assertTrue(done.isSucceeded(), "retry policy saves the run");

        assertEquals(2, recorder.distinctKeys(),
                "THE RETRY TRAP: two attempts minted two keys (:1, :2)");
        assertEquals(2, recorder.deliveredKeys(),
                "one logical visit, two external deliveries - the attempt "
                        + "counter keys the RETRY, not the visit");
    }

    /**
     * The trace-derived ordinal survives retries: a retried visit has
     * no SUCCESS record for itself yet (records are written only after
     * success), so every attempt derives the SAME key - the retry
     * dedupes on the external system.
     */
    @Test
    void scenario5b_traceKeyedNodeDedupesOnRetry() {
        SideEffectRecorder recorder = new SideEffectRecorder();
        FlakyChargeNode flaky = new FlakyChargeNode(recorder, false);
        Workflow wf = Workflow.builder("e8-retry-trace-key")
                .node(flaky, RetryPolicy.fixed(1, 0))
                .edge(Workflow.START, "flaky")
                .edge("flaky", Workflow.END)
                .build();

        RunManager mgr = new RunManager();
        ExecutionResult done = mgr.start(wf, "input");
        assertTrue(done.isSucceeded());

        assertEquals(1, recorder.distinctKeys(),
                "both attempts derived the SAME key (zero SUCCESS records "
                        + "for flaky until the retry succeeded)");
        assertEquals(1, recorder.deliveredKeys(),
                "one logical visit, one delivery - the retry deduped");
    }

    // ============ Scenario 6: honest boundary (granularity = last pause) ============

    /** A(->approval)->B->C: pause mid-flow, complete both sides. */
    private static Workflow granularityFlow(ApprovalService approvals, WorkflowNode a,
                                             WorkflowNode b, WorkflowNode c) {
        return Workflow.builder("e8-granularity")
                .node(a)
                .node(HumanApprovalNode.of("approval", "mid-flow?", approvals))
                .node(b)
                .node(c)
                .edge(Workflow.START, "A")
                .edge("A", "approval")
                .edge("approval", "B")
                .edge("B", "C")
                .edge("C", Workflow.END)
                .build();
    }

    /**
     * RunManager persists checkpoints ONLY on PAUSED - terminal runs
     * write nothing. The recovery point granularity is therefore
     * "since the last pause", not "since the last node": a crash after
     * several post-pause nodes completed replays ALL of them. Node A
     * (pre-pause) is protected by the cursor; nodes B and C (post-pause,
     * completed pre-crash) are replayed in full - the gap is not just
     * inside nodes, it is the whole non-paused span.
     */
    @Test
    void scenario6_recoveryGranularityIsTheLastPauseNotTheLastNode(
            @TempDir Path checkpointDir) {
        SideEffectRecorder recorder = new SideEffectRecorder();

        // ---- generation 1: pause at approval, then complete A->approval->B->C ----
        MockApprovalService svc1 = MockApprovalService.autoApprove();
        Workflow wf1 = granularityFlow(svc1,
                new UnguardedChargeNode("A", recorder),
                new UnguardedChargeNode("B", recorder),
                new UnguardedChargeNode("C", recorder));
        RunManager gen1 = restartedManager(checkpointDir);
        ExecutionResult first = gen1.start(wf1, Map.of("order", "O-2"));
        assertTrue(first.isPaused());
        String runId = first.resumeToken().runId();
        svc1.setDecision(runId, "approval", true);
        assertTrue(gen1.resume(runId).isSucceeded());
        assertEquals(1, recorder.rawCalls("A"));
        assertEquals(1, recorder.rawCalls("B"));
        assertEquals(1, recorder.rawCalls("C"));

        // ---- generation 2: crash replay from the approval checkpoint ----
        MockApprovalService svc2 = MockApprovalService.autoApprove();
        Workflow wf2 = granularityFlow(svc2,
                new UnguardedChargeNode("A", recorder),
                new UnguardedChargeNode("B", recorder),
                new UnguardedChargeNode("C", recorder));
        RunManager gen2 = restartedManager(checkpointDir);
        ExecutionResult recovered = gen2.resume(runId, wf2);
        assertTrue(recovered.isPaused());
        svc2.setDecision(runId, "approval", true);
        assertTrue(gen2.resume(runId).isSucceeded());

        assertEquals(1, recorder.rawCalls("A"),
                "A completed BEFORE the pause: cursor protection holds");
        assertEquals(2, recorder.rawCalls("B"),
                "B completed AFTER the pause but pre-crash: replayed - the "
                        + "recovery granularity is the pause, not the node");
        assertEquals(2, recorder.rawCalls("C"),
                "C same as B: the whole non-paused span replays");
    }
}

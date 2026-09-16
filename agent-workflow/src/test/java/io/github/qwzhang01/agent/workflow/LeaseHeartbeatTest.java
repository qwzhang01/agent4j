package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;
import io.github.qwzhang01.agent.workflow.runtime.DurableRunManager;
import io.github.qwzhang01.agent.workflow.runtime.InMemoryCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import io.github.qwzhang01.agent.workflow.runtime.durable.InMemoryRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunLeases;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunStore;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8.1 heartbeat fix (the real bug this stage started with): a resume
 * holding a 60s-TTL lease never renewed it, so a legitimately long resume
 * looked like a crashed holder — another worker took over mid-flight and
 * BOTH executed. The fix: a daemon heartbeat thread renews every TTL/3
 * while the resume is in flight; when renewal fails (ownership lost) the
 * in-flight run is cancelled at the next node boundary.
 * <p>
 * Uses a two-phase node: the first execution pauses (start returns
 * PAUSED quickly), the resume path blocks on a latch so the heartbeat's
 * behavior is observable without racing the start path.
 */
class LeaseHeartbeatTest {

    /** Lease backend with a controllable effective TTL for tests. */
    private static final class ShortTtlLeases implements RunLeases {
        final io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry delegate =
                new io.github.qwzhang01.agent.workflow.runtime.durable.RunLeaseRegistry();
        final long ttlMillis;
        final AtomicInteger renewCount = new AtomicInteger();
        volatile boolean stealOnRenew = false;

        ShortTtlLeases(long ttlMillis) {
            this.ttlMillis = ttlMillis;
        }

        @Override
        public boolean tryAcquire(String runId, String holder, long ttlMillis) {
            return delegate.tryAcquire(runId, holder, this.ttlMillis);
        }

        @Override
        public boolean renew(String runId, String holder, long ttlMillis) {
            renewCount.incrementAndGet();
            if (stealOnRenew) {
                return false; // simulate: another worker took the lease over
            }
            return delegate.renew(runId, holder, this.ttlMillis);
        }

        @Override
        public boolean release(String runId, String holder) {
            return delegate.release(runId, holder);
        }

        @Override
        public boolean isHeld(String runId) {
            return delegate.isHeld(runId);
        }

        @Override
        public Optional<String> holder(String runId) {
            return delegate.holder(runId);
        }
    }

    /**
     * Two-phase slow node: first execution pauses (Phase 1); the resume
     * execution (Phase 2, {@code ctx.isResuming()}) signals entry then
     * blocks on the latch — holding the resume in-flight well past any
     * test TTL, exactly the "legitimately long resume" the bug targeted.
     */
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
            throw new PauseException("slow", "waiting before the long settle");
        }
    }

    private static Workflow workflowOf(SlowTwoPhaseNode node,
                                       java.util.concurrent.atomic.AtomicInteger sentinelCounter) {
        return Workflow.builder("hb-flow").version("1.0")
                .node(node)
                .node(io.github.qwzhang01.agent.workflow.nodes.ActionNode.of("sentinel", ctx -> {
                    sentinelCounter.incrementAndGet();
                    return "sentinel-ok";
                }))
                .edge(Workflow.START, node.id())
                .edge(node.id(), "sentinel")
                .edge("sentinel", Workflow.END)
                .build();
    }

    /** Counts sentinel executions for THIS test instance (JUnit creates one per test). */
    private final java.util.concurrent.atomic.AtomicInteger sentinelReached =
            new java.util.concurrent.atomic.AtomicInteger();

    @Test
    void longResumeIsNotTakenOverWhileHeartbeatHolds() throws Exception {
        // TTL 500ms; heartbeat period >=250ms; resume blocks for 1.5s+ —
        // far beyond the TTL. Without the heartbeat fix, the lease would
        // expire and a takeover would succeed (duplicate execution bug).
        ShortTtlLeases leases = new ShortTtlLeases(500);
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        Workflow wf = workflowOf(new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinelReached);

        RunManager rm = new RunManager(new InMemoryCheckpointStore());
        RunStore store = new InMemoryRunStore();
        DurableRunManager durable = new DurableRunManager(rm, store, leases, null, 500);

        ExecutionResult first = durable.start(wf, "in", "run-hb", null);
        assertTrue(first.isPaused(), "phase 1 must pause the run");

        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> result.set(durable.resume("run-hb", wf)));
        worker.start();
        assertTrue(resumeEntered.await(5, TimeUnit.SECONDS), "resume phase must start");
        // Hold past the original TTL while the heartbeat keeps renewing.
        Thread.sleep(1_500);
        assertTrue(leases.renewCount.get() >= 1, "heartbeat must have renewed at least once");
        // Another worker cannot take the lease: the holder is alive.
        assertFalse(leases.tryAcquire("run-hb", "other-worker", 60_000),
                "heartbeat must keep the lease take-proof while resume is in flight");

        releaseResume.countDown();
        worker.join(5_000);
        assertEquals(ExecutionResult.Status.SUCCEEDED, result.get().status(),
                "the resumed run must finish once released");
        // After resume returns, the lease is released: takeover works again.
        assertTrue(leases.tryAcquire("run-hb", "other-worker", 60_000));
    }

    @Test
    void lostOwnershipCancelsInFlightRun() throws Exception {
        ShortTtlLeases leases = new ShortTtlLeases(500);
        leases.stealOnRenew = true; // renew reports: ownership lost

        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        Workflow wf = workflowOf(new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinelReached);

        RunManager rm = new RunManager(new InMemoryCheckpointStore());
        RunStore store = new InMemoryRunStore();
        DurableRunManager durable = new DurableRunManager(rm, store, leases, null, 500);
        ExecutionResult first = durable.start(wf, "in", "run-lost", null);
        assertTrue(first.isPaused());

        AtomicReference<ExecutionResult> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                result.set(durable.resume("run-lost", wf));
            } catch (RuntimeException e) {
                result.set(null); // loud failure is also acceptable
            }
        });
        worker.start();
        assertTrue(resumeEntered.await(5, TimeUnit.SECONDS));
        // Wait until at least one failed renew was observed.
        long deadline = System.currentTimeMillis() + 5_000;
        while (leases.renewCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(leases.renewCount.get() > 0, "heartbeat must have attempted a renew");
        Thread.sleep(300); // let the cancel land
        releaseResume.countDown();
        worker.join(5_000);

        // The in-flight run must NOT silently succeed after losing ownership.
        ExecutionResult r = result.get();
        assertTrue(r == null || r.status() == ExecutionResult.Status.CANCELLED,
                "lost ownership must stop the run, got: "
                        + (r == null ? "thrown" : r.status()));
        assertEquals(0, sentinelReached.get(),
                "the sentinel node after the slow node must never execute once "
                        + "ownership was lost (duplicate execution guard)");
    }

    @Test
    void concurrentResumeSecondWorkerFailsLoudly() throws Exception {
        ShortTtlLeases leases = new ShortTtlLeases(60_000);
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch releaseResume = new CountDownLatch(1);
        Workflow wf = workflowOf(new SlowTwoPhaseNode(resumeEntered, releaseResume), sentinelReached);

        RunManager rm = new RunManager(new InMemoryCheckpointStore());
        RunStore store = new InMemoryRunStore();
        DurableRunManager durable = new DurableRunManager(rm, store, leases, null, 60_000);
        ExecutionResult first = durable.start(wf, "in", "run-cc", null);
        assertTrue(first.isPaused());

        Thread workerA = new Thread(() -> durable.resume("run-cc", wf));
        workerA.start();
        assertTrue(resumeEntered.await(5, TimeUnit.SECONDS));
        WorkflowException ex = assertThrows(WorkflowException.class,
                () -> durable.resume("run-cc", wf));
        assertTrue(ex.getMessage().contains("leased"), "conflict must name the lease: "
                + ex.getMessage());
        releaseResume.countDown();
        workerA.join(5_000);
    }
}

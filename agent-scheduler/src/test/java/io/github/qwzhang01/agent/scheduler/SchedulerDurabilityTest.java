package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 3.5 acceptance (harness roadmap): scheduler durability +
 * backpressure.
 * <ul>
 *   <li>queue at capacity refuses with an explicit, classified event</li>
 *   <li>recovery candidates come from the durable RunStore, not JVM memory</li>
 *   <li>runs actively tracked in this process are not double-scheduled</li>
 * </ul>
 */
class SchedulerDurabilityTest {

    private static io.github.qwzhang01.agent.scheduler.AsyncTask task(String id) {
        io.github.qwzhang01.agent.scheduler.AsyncTask base =
                AsyncTask.of("run-r", java.util.Map.of(), TaskPriority.NORMAL, "do work");
        return new AsyncTask(id, base.parentRunId(), base.input(), base.priority(),
                base.status(), base.workflowName(), base.createdAt(),
                base.startedAt(), base.completedAt(), base.result());
    }

    @Test
    void fullQueueRejectsWithClassifiedEvent() {
        AsyncTaskQueue queue = new AsyncTaskQueue(2);
        queue.enqueue(task("t1"));
        queue.enqueue(task("t2"));

        AsyncTaskQueue.QueueFullException ex =
                assertThrows(AsyncTaskQueue.QueueFullException.class,
                        () -> queue.enqueue(task("t3")),
                        "capacity 2 must refuse the third task");
        assertTrue(ex.getMessage().contains("[QUEUE_FULL]"),
                "rejection must be explicitly classified: " + ex.getMessage());
        assertEquals(1, queue.totalRejected());
        assertEquals(2, queue.size());

        // Consuming frees capacity again
        queue.pollNext();
        assertDoesNotThrow(() -> queue.enqueue(task("t4")));
        assertEquals(1, queue.totalRejected(), "no further rejection after capacity freed");
    }

    @Test
    void unboundedQueueKeepsLegacyBehavior() {
        AsyncTaskQueue queue = new AsyncTaskQueue();
        for (int i = 0; i < 1000; i++) {
            queue.enqueue(task("t" + i));
        }
        assertEquals(1000, queue.size());
        assertEquals(0, queue.totalRejected());
    }

    @Test
    void recoverySweepUsesRunStoreNotMemory() {
        // Two recovery candidates on disk, one active in this process.
        var runStore = new io.github.qwzhang01.agent.workflow.runtime.durable.InMemoryRunStore();
        runStore.create(new io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord(
                "r-from-disk", "wf", "1.0", "h", "WAITING_APPROVAL",
                "approve", 1, 4, null, null, 1, 1, 0, List.of()));
        runStore.create(new io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord(
                "r-also-disk", "wf", "1.0", "h", "RUNNING",
                null, 0, 0, null, null, 1, 1, 0, List.of()));
        runStore.create(new io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord(
                "r-terminal", "wf", "1.0", "h", "SUCCEEDED",
                null, 5, 9, null, null, 1, 1, 0, List.of()));

        var rm = new io.github.qwzhang01.agent.workflow.runtime.RunManager(
                new io.github.qwzhang01.agent.workflow.runtime.InMemoryCheckpointStore());
        var durable = new io.github.qwzhang01.agent.workflow.runtime.DurableRunManager(rm, runStore);

        TaskScheduler scheduler = new TaskScheduler(rm);
        try {
            scheduler.start();
            int scheduled = scheduler.restoreDurableRuns(durable, Duration.ofMillis(50));
            assertEquals(2, scheduled, "two disk candidates, terminal excluded, none active here");

            var resumes = scheduler.getScheduledResumes();
            assertTrue(resumes.values().stream()
                    .anyMatch(sr -> sr.runId().equals("r-from-disk")));
            assertTrue(resumes.values().stream()
                    .anyMatch(sr -> sr.runId().equals("r-also-disk")));
            assertFalse(resumes.values().stream()
                    .anyMatch(sr -> sr.runId().equals("r-terminal")),
                    "terminal runs are never recovery candidates");
        } finally {
            scheduler.shutdown();
        }
    }
}

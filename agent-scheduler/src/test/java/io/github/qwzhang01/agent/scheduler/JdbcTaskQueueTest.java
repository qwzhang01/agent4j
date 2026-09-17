package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8.1: {@link JdbcTaskQueue} contract — claim is single-winner via
 * guarded UPDATE, priority DESC + seq ASC ordering, terminal-only
 * complete/fail, PENDING-only cancel, orphan requeue with grace window.
 */
class JdbcTaskQueueTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcTaskQueue queue;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:tasks_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        queue = new JdbcTaskQueue(conn);
        queue.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void initializeIsIdempotent() {
        queue.initialize(); // second call must not throw
    }

    @Test
    void enqueueThenGetRoundTrips() {
        JdbcTaskQueue.TaskRow row = queue.enqueue("run-1", "demo-flow",
                TaskPriority.NORMAL, "{\"input\": 1}");
        assertEquals(TaskStatus.PENDING, row.status());
        assertEquals(TaskPriority.NORMAL, row.priority());
        assertEquals("{\"input\": 1}", row.payload());
        assertNull(row.result());
        assertNotNull(row.taskId());

        JdbcTaskQueue.TaskRow loaded = queue.get(row.taskId()).orElseThrow();
        assertEquals(row.taskId(), loaded.taskId());
        assertEquals("run-1", loaded.parentRunId());
        assertEquals("demo-flow", loaded.workflowName());
        assertEquals(TaskStatus.PENDING, loaded.status());
    }

    @Test
    void claimMarksRunningAndSingleWinner() {
        JdbcTaskQueue.TaskRow t1 = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p1");
        JdbcTaskQueue.TaskRow claimed = queue.claimNext("worker-a");
        assertNotNull(claimed);
        assertEquals(t1.taskId(), claimed.taskId());
        assertEquals(TaskStatus.RUNNING, claimed.status());
        assertNotNull(claimed.startedAt());

        // No other PENDING task: second claim returns null.
        assertNull(queue.claimNext("worker-b"));
    }

    @Test
    void claimOrderIsPriorityDescThenFifo() {
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "n1");   // seq 1
        queue.enqueue(null, "flow", TaskPriority.URGENT, "u1");   // seq 2
        queue.enqueue(null, "flow", TaskPriority.LOW, "l1");      // seq 3
        queue.enqueue(null, "flow", TaskPriority.URGENT, "u2");   // seq 4
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "n2");   // seq 5

        assertEquals("u1", queue.claimNext("w").payload());
        assertEquals("u2", queue.claimNext("w").payload());
        assertEquals("n1", queue.claimNext("w").payload());
        assertEquals("n2", queue.claimNext("w").payload());
        assertEquals("l1", queue.claimNext("w").payload());
        assertNull(queue.claimNext("w"));
    }

    @Test
    void completeMarksSucceededWithResult() {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        JdbcTaskQueue.TaskRow done = queue.complete(t.taskId(), "done-value");
        assertEquals(TaskStatus.SUCCEEDED, done.status());
        assertEquals("done-value", done.result());
        assertNotNull(done.completedAt());
    }

    @Test
    void completeOnNonRunningFails() {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        // PENDING (never claimed): complete must fail loudly.
        assertThrows(IllegalStateException.class, () -> queue.complete(t.taskId(), "x"));
        queue.claimNext("w");
        queue.complete(t.taskId(), "done");
        // Terminal already: second complete must fail.
        assertThrows(IllegalStateException.class, () -> queue.complete(t.taskId(), "x"));
    }

    @Test
    void failMarksFailedTerminal() {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("w");
        JdbcTaskQueue.TaskRow failed = queue.fail(t.taskId());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertNotNull(failed.completedAt());
        assertThrows(IllegalStateException.class, () -> queue.fail(t.taskId()));
    }

    @Test
    void cancelOnlyPending() {
        JdbcTaskQueue.TaskRow pending = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p1");
        JdbcTaskQueue.TaskRow cancelled = queue.cancel(pending.taskId());
        assertEquals(TaskStatus.CANCELLED, cancelled.status());
        assertNotNull(cancelled.completedAt());

        JdbcTaskQueue.TaskRow running = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p2");
        queue.claimNext("w");
        // RUNNING cannot be cancelled through this path.
        assertThrows(IllegalStateException.class, () -> queue.cancel(running.taskId()));
    }

    @Test
    void requeueOrphanedOnlyTouchesStaleRunning() throws SQLException {
        JdbcTaskQueue.TaskRow fresh = queue.enqueue(null, "flow", TaskPriority.NORMAL, "f");
        queue.claimNext("worker-a"); // fresh RUNNING: inside grace

        // Simulate a crashed worker: claim, then backdate started_at.
        JdbcTaskQueue.TaskRow orphan = queue.enqueue(null, "flow", TaskPriority.NORMAL, "o");
        queue.claimNext("worker-b");
        try (var st = conn.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET started_at = 1000 WHERE task_id = '"
                    + orphan.taskId() + "'");
        }

        // Also enqueue a never-claimed PENDING task: must NOT be reported.
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "never-claimed");

        List<String> requeued = queue.requeueOrphaned(60_000);
        assertEquals(1, requeued.size());
        assertEquals(orphan.taskId(), requeued.get(0));

        assertEquals(TaskStatus.PENDING, queue.get(orphan.taskId()).orElseThrow().status());
        assertEquals(TaskStatus.RUNNING, queue.get(fresh.taskId()).orElseThrow().status());
    }

    @Test
    void requeuedOrphanIsClaimableAgain() throws SQLException {
        JdbcTaskQueue.TaskRow orphan = queue.enqueue(null, "flow", TaskPriority.NORMAL, "o");
        queue.claimNext("worker-a");
        try (var st = conn.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET started_at = 1000 WHERE task_id = '"
                    + orphan.taskId() + "'");
        }
        queue.requeueOrphaned(60_000);
        // Another worker claims it: single-winner still applies.
        JdbcTaskQueue.TaskRow reclaimed = queue.claimNext("worker-b");
        assertNotNull(reclaimed);
        assertEquals(orphan.taskId(), reclaimed.taskId());
        assertEquals(TaskStatus.RUNNING, reclaimed.status());
    }

    @Test
    void listPendingAndCountByStatus() {
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "a");
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "b");
        queue.enqueue(null, "flow", TaskPriority.NORMAL, "c");
        JdbcTaskQueue.TaskRow claimed = queue.claimNext("w");
        assertEquals("a", claimed.payload(), "FIFO head is claimed first");
        assertEquals(2, queue.listPending().size());
        assertEquals(1, queue.countByStatus(TaskStatus.RUNNING));
        assertEquals(3, queue.countByStatus(TaskStatus.PENDING) + queue.countByStatus(TaskStatus.RUNNING));
        assertTrue(queue.listPending().stream()
                .noneMatch(t -> t.taskId().equals(claimed.taskId())));
    }
}

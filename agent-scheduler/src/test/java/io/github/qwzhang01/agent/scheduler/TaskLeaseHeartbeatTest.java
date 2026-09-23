package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness batch 5: the task-level lease closes its two gaps — {@link
 * JdbcTaskQueue#requeueOrphaned(long)} gets a periodic caller ({@link
 * TaskLeaseSweeper}), and a claimed task gets an independent lease
 * clock ({@code heartbeat_at} + {@link JdbcTaskQueue#heartbeat(String)}
 * + the {@link ClaimedTaskHeartbeat} holder) instead of leaning only on
 * the grace window since claim.
 */
class TaskLeaseHeartbeatTest {

    static {
        // Same fixture contract as JdbcTaskQueueTest: force H2 class init
        // so driver registration never races the shared-JVM ServiceLoader.
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcTaskQueue queue;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:lease_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        queue = new JdbcTaskQueue(conn);
        queue.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    /** Simulate a crashed or stalled holder: age the lease far past any grace. */
    private static void backdate(Connection c, String taskId) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET started_at = 1000, heartbeat_at = 1000 "
                    + "WHERE task_id = '" + taskId + "'");
        }
    }

    /** Poll a condition until true or the deadline passes (20ms steps). */
    private static void awaitTrue(long timeoutMillis, BooleanSupplier cond)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (!cond.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }

    @Test
    void claimInitializesTheLeaseClock() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        try (Statement st = conn.createStatement();
             var rs = st.executeQuery("SELECT started_at, heartbeat_at FROM agent4j_tasks "
                     + "WHERE task_id = '" + t.taskId() + "'")) {
            rs.next();
            assertTrue(rs.getLong(1) > 0, "claim stamps started_at");
            assertEquals(rs.getLong(1), rs.getLong(2),
                    "claim must stamp heartbeat_at (the lease clock) together with started_at");
        }
    }

    @Test
    void heartbeatRenewsARunningTask() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET heartbeat_at = 1000 WHERE task_id = '"
                    + t.taskId() + "'");
        }
        assertTrue(queue.heartbeat(t.taskId()), "a RUNNING task must renew");
        assertTrue(queue.heartbeatAt(t.taskId()).orElseThrow().toEpochMilli() > 1_000_000_000L,
                "the renewal stamps the lease clock now");
    }

    @Test
    void heartbeatRefusesNonRunningRows() {
        JdbcTaskQueue.TaskRow pending = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        assertFalse(queue.heartbeat(pending.taskId()), "PENDING (never claimed) has no lease");
        queue.cancel(pending.taskId()); // clear the PENDING pool so claimNext reaches done

        JdbcTaskQueue.TaskRow done = queue.enqueue(null, "flow", TaskPriority.NORMAL, "d");
        queue.claimNext("w");
        queue.complete(done.taskId(), "ok");
        assertFalse(queue.heartbeat(done.taskId()), "terminal rows cannot renew");
    }

    @Test
    void heartbeatKeepsALongRunSafeFromTheSweep() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        backdate(conn, t.taskId()); // the claim was ages ago (long task)
        assertTrue(queue.heartbeat(t.taskId()), "the holder renews right now");
        assertTrue(queue.requeueOrphaned(60_000).isEmpty(),
                "a heartbeating holder keeps its lease no matter how long the task runs");
        assertEquals(TaskStatus.RUNNING, queue.get(t.taskId()).orElseThrow().status());
    }

    @Test
    void neverHeartbeatedTaskIsJudgedByClaimTime() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        // A holder that never renews: the lease clock falls back to claim time.
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET started_at = 1000, heartbeat_at = NULL "
                    + "WHERE task_id = '" + t.taskId() + "'");
        }
        assertEquals(List.of(t.taskId()), queue.requeueOrphaned(60_000));
    }

    @Test
    void graceWindowToleratesBriefSilence() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        long leaseAge = 30_000; // inside the 60s grace
        long now = System.currentTimeMillis();
        try (Statement st = conn.createStatement()) {
            st.execute("UPDATE agent4j_tasks SET started_at = " + (now - leaseAge)
                    + ", heartbeat_at = " + (now - leaseAge)
                    + " WHERE task_id = '" + t.taskId() + "'");
        }
        assertTrue(queue.requeueOrphaned(60_000).isEmpty(),
                "a lease inside the grace window must survive the sweep");
    }

    @Test
    void aStalledHeartbeatIsRequeuedAndReclaimed() throws SQLException {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        backdate(conn, t.taskId()); // holder stalled: last sign of life aged out
        assertEquals(List.of(t.taskId()), queue.requeueOrphaned(60_000));
        JdbcTaskQueue.TaskRow reclaimed = queue.claimNext("worker-b");
        assertNotNull(reclaimed, "another worker must be able to claim the requeued task");
        assertEquals(t.taskId(), reclaimed.taskId());
        assertTrue(queue.heartbeatAt(t.taskId()).orElseThrow().toEpochMilli() > 1_000_000_000L,
                "the new claim restarts the lease clock");
    }

    @Test
    void claimedTaskHeartbeatKeepsTheLeaseAlive() throws Exception {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        try (ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(queue, t.taskId(), 40)) {
            backdate(conn, t.taskId()); // task runs far past any grace window
            Thread.sleep(150);          // at least one renewal fired
            assertTrue(queue.requeueOrphaned(60_000).isEmpty(),
                    "the holder's renewals keep the lease despite the ancient claim");
            assertFalse(hb.lost());
        }
    }

    @Test
    void holderDetectsWhenTheRowLeavesRunning() throws Exception {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        try (ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(queue, t.taskId(), 40)) {
            // The row completes (or is cancelled / requeued) under the holder.
            queue.complete(t.taskId(), "done-elsewhere");
            awaitTrue(5_000, hb::lost);
            assertTrue(hb.lost(), "a renewal against a non-RUNNING row must flag the loss");
        }
    }

    @Test
    void closingTheHeartbeatStopsRenewal() throws Exception {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-a");
        ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(queue, t.taskId(), 40);
        hb.close();
        backdate(conn, t.taskId());
        Thread.sleep(120); // no renewal may fire after close
        assertEquals(List.of(t.taskId()), queue.requeueOrphaned(60_000),
                "after close the lease ages into sweep range");
        hb.close(); // idempotent
    }

    @Test
    void holderSurvivesStoreErrorsWithoutLosingTheLease() throws Exception {
        Connection flaky = DriverManager.getConnection(
                "jdbc:h2:mem:flakyhb_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTaskQueue flakyQueue = new JdbcTaskQueue(flaky);
        flakyQueue.initialize();
        JdbcTaskQueue.TaskRow t = flakyQueue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        flakyQueue.claimNext("worker-a");
        flaky.close(); // store outage: every renewal now fails
        try (ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(flakyQueue, t.taskId(), 40)) {
            awaitTrue(5_000, () -> hb.errors() >= 1);
            assertTrue(hb.errors() >= 1, "the failed renewal must be counted");
            assertFalse(hb.lost(), "a store blip must not read as a lost lease");
        }
    }

    @Test
    void sweeperRequeuesPeriodically() throws Exception {
        JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        queue.claimNext("worker-crashed");
        backdate(conn, t.taskId());
        try (TaskLeaseSweeper sweeper = new TaskLeaseSweeper(queue, 40, 60_000)) {
            awaitTrue(5_000, () -> queue.get(t.taskId()).orElseThrow().status()
                    != TaskStatus.RUNNING);
            assertEquals(TaskStatus.PENDING, queue.get(t.taskId()).orElseThrow().status(),
                    "the sweeper must requeue the crashed holder's task");
            assertTrue(sweeper.totalSweeps() >= 1);
            assertTrue(sweeper.totalRequeued() >= 1);
            assertEquals(0, sweeper.totalErrors());
        }
    }

    @Test
    void sweeperSurvivesStoreErrors() throws Exception {
        Connection flaky = DriverManager.getConnection(
                "jdbc:h2:mem:flakysweep_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        JdbcTaskQueue flakyQueue = new JdbcTaskQueue(flaky);
        flakyQueue.initialize();
        JdbcTaskQueue.TaskRow t = flakyQueue.enqueue(null, "flow", TaskPriority.NORMAL, "p");
        flakyQueue.claimNext("worker-crashed");
        backdate(flaky, t.taskId());
        flaky.close(); // store outage: every sweep now fails
        try (TaskLeaseSweeper sweeper = new TaskLeaseSweeper(flakyQueue, 40, 60_000)) {
            awaitTrue(5_000, () -> sweeper.totalErrors() >= 1);
            assertTrue(sweeper.totalErrors() >= 1, "the failed sweep must be counted");
            assertEquals(0, sweeper.totalRequeued());
        }
    }

    @Test
    void constructorRejectsNonPositiveIntervals() {
        assertThrows(IllegalArgumentException.class,
                () -> new ClaimedTaskHeartbeat(queue, "t", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskLeaseSweeper(queue, 0, 60_000));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskLeaseSweeper(queue, 1_000, -1));
    }
}

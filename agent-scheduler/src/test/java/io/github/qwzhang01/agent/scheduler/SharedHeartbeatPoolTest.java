package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Harness batch 7: the shared-pool heartbeat shape — many holders on ONE
 * host-owned {@link ScheduledExecutorService}, close() cancelling only
 * the holder's own future, the pool never shut down by a holder.
 */
class SharedHeartbeatPoolTest {

    static {
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcTaskQueue queue;

    @org.junit.jupiter.api.BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:sharedhb_"
                + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        queue = new JdbcTaskQueue(conn);
        queue.initialize();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    void manyHoldersRideOneSharedPool() throws Exception {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(2);
        try {
            int tasks = 20;
            for (int i = 0; i < tasks; i++) {
                JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow",
                        TaskPriority.NORMAL, "p" + i);
                queue.claimNext("worker-" + i);
                try (ClaimedTaskHeartbeat hb =
                        new ClaimedTaskHeartbeat(queue, t.taskId(), 40, pool)) {
                    assertNotNull(hb.taskId());
                }
            }
            // All holders closed cleanly on the shared pool: no assertion on
            // thread count (an implementation detail), the contract is that
            // close() never shuts the pool down — exercised by the renewals
            // below still being schedulable.
            JdbcTaskQueue.TaskRow extra = queue.enqueue(null, "flow",
                    TaskPriority.NORMAL, "extra");
            queue.claimNext("worker-extra");
            try (ClaimedTaskHeartbeat hb =
                    new ClaimedTaskHeartbeat(queue, extra.taskId(), 40, pool)) {
                Thread.sleep(150);
                assertFalse(hb.lost());
                assertTrue(queue.heartbeatAt(extra.taskId()).orElseThrow()
                                .toEpochMilli() > 1_000_000_000L,
                        "renewal fired on the shared pool after prior closes");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void closeNeverShutsDownTheSharedPool() throws Exception {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
        try {
            JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow",
                    TaskPriority.NORMAL, "p");
            queue.claimNext("worker-a");
            ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(queue, t.taskId(), 40, pool);
            hb.close();
            assertFalse(pool.isShutdown(), "a holder must never shut down the shared pool");
            hb.close(); // idempotent
            assertFalse(pool.isShutdown());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sharedPoolHolderDetectsLeaseLoss() throws Exception {
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1);
        try {
            JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow",
                    TaskPriority.NORMAL, "p");
            queue.claimNext("worker-a");
            ClaimedTaskHeartbeat hb = new ClaimedTaskHeartbeat(queue, t.taskId(), 40, pool);
            queue.complete(t.taskId(), "done-elsewhere");
            long deadline = System.currentTimeMillis() + 5_000;
            while (!hb.lost() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertTrue(hb.lost(), "shared-pool holder must still detect the ownership loss");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void threadFactoryReusesPoolThreadsAcrossHolders() throws Exception {
        // One shared pool with one thread: sequential holders must reuse it
        // (the thread count stays 1, not one thread per holder).
        AtomicInteger created = new AtomicInteger();
        ScheduledExecutorService pool = Executors.newScheduledThreadPool(1, r -> {
            created.incrementAndGet();
            Thread t = new Thread(r, "shared-hb");
            t.setDaemon(true);
            return t;
        });
        try {
            for (int i = 0; i < 5; i++) {
                JdbcTaskQueue.TaskRow t = queue.enqueue(null, "flow",
                        TaskPriority.NORMAL, "p" + i);
                queue.claimNext("worker-" + i);
                try (ClaimedTaskHeartbeat hb =
                        new ClaimedTaskHeartbeat(queue, t.taskId(), 20, pool)) {
                    Thread.sleep(60); // at least one renewal scheduled and run
                }
            }
            assertEquals(1, created.get(),
                    "five holders must share one pool thread, not five");
        } finally {
            pool.shutdownNow();
        }
    }
}

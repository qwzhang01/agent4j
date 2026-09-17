package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 8.1 (2026-09-17): backpressure and tenancy on the persistent
 * task table — the capacity guard meters non-terminal occupancy
 * (PENDING + RUNNING) and refuses at capacity with
 * {@link JdbcTaskQueue.QueueFullException}; the tenant column isolates
 * one tenant's in-flight rows from everyone else's.
 */
class JdbcTaskQueueBackpressureTest {

    static {
        org.h2.Driver.load();
    }

    private Connection conn;
    private String url;
    private JdbcTaskQueue bounded;
    private JdbcTaskQueue unbounded;

    @BeforeEach
    void setUp() throws SQLException {
        // One named in-mem DB, kept alive across connections (DB_CLOSE_DELAY=-1):
        // the bounded queue's supplier hands out a FRESH connection per operation
        // (pool semantics — the queue closes what the supplier gave it), while
        // `conn` stays open for setup/teardown.
        url = "jdbc:h2:mem:tasks_bp_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        conn = DriverManager.getConnection(url);
        unbounded = new JdbcTaskQueue(conn);
        unbounded.initialize();
        bounded = new JdbcTaskQueue(() -> DriverManager.getConnection(url), 2);
        bounded.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    @Test
    @DisplayName("capacity guard: 3rd enqueue against capacity 2 is refused loudly")
    void enqueueBeyondCapacityThrowsQueueFull() {
        bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p1", null);
        bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p2", null);
        JdbcTaskQueue.QueueFullException e = assertThrows(
                JdbcTaskQueue.QueueFullException.class,
                () -> bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p3", null));
        assertTrue(e.getMessage().contains("[QUEUE_FULL]"), "signal family marker present");
        assertEquals(1, bounded.totalRejected(), "the rejection counter meters the refusal");
        assertEquals(2, bounded.activeCount(), "non-terminal occupancy is what got metered");
    }

    @Test
    @DisplayName("terminal rows free capacity: COMPLETE releases a slot for the next enqueue")
    void completingTaskReleasesCapacity() {
        JdbcTaskQueue.TaskRow t1 = bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p1", null);
        bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p2", null);
        bounded.claimNext("worker-a"); // claims t1 (FIFO head) → RUNNING
        bounded.complete(t1.taskId(), "done"); // SUCCEEDED is terminal: slot freed
        JdbcTaskQueue.TaskRow t3 = bounded.enqueue(null, "flow", TaskPriority.NORMAL, "p3", null);
        assertEquals(TaskStatus.PENDING, t3.status(), "freed slot admits the next task");
        assertEquals(2, bounded.activeCount(), "occupancy back at capacity, not over");
    }

    @Test
    @DisplayName("capacity <= 0 means unbounded: legacy constructor behavior")
    void zeroCapacityMeansUnbounded() {
        for (int i = 1; i <= 5; i++) {
            unbounded.enqueue(null, "flow", TaskPriority.NORMAL, "p" + i, null);
        }
        assertEquals(5, unbounded.activeCount(), "no capacity configured: no refusals");
        assertEquals(0, unbounded.totalRejected());
    }

    @Test
    @DisplayName("tenant column round-trips and listByTenant isolates non-terminal rows")
    void tenantTagIsolatesListByTenant() {
        unbounded.enqueue(null, "flow", TaskPriority.NORMAL, "t1-a", "tenant-a");
        unbounded.enqueue(null, "flow", TaskPriority.NORMAL, "t1-b", "tenant-a");
        unbounded.enqueue(null, "flow", TaskPriority.NORMAL, "t2-c", "tenant-b");

        JdbcTaskQueue.TaskRow claimed = unbounded.claimNext("w"); // t1-a → RUNNING
        assertEquals("t1-a", claimed.payload());
        assertEquals("tenant-a", claimed.tenantId(), "claim carries the tenant tag");

        // Tenant-a still has 2 in-flight rows (1 RUNNING + 1 PENDING); tenant-b has 1.
        assertEquals(2, unbounded.listByTenant("tenant-a").size());
        assertEquals(1, unbounded.listByTenant("tenant-b").size());

        // Terminal rows drop out of the tenant view.
        unbounded.complete(claimed.taskId(), "done");
        assertEquals(1, unbounded.listByTenant("tenant-a").size());
        assertTrue(unbounded.listByTenant("tenant-z").isEmpty(),
                "unknown tenant: no rows, not an error");
    }
}

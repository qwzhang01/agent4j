package io.github.qwzhang01.agent.scheduler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8.3: {@link JdbcTaskQueue}'s dialect discipline proven on real
 * PostgreSQL (skip-by-assumption when none is reachable). The H2 suite (11
 * tests) stays the full contract; this is the dialect proof.
 */
@Tag(PostgresIT.TAG)
class PostgresTaskQueueIT extends PostgresIT {

    @Test
    void taskQueueCoreContractOnPostgres() throws Exception {
        try (Connection conn = openConnection(); SchemaHandle schema = freshSchema(conn)) {
            JdbcTaskQueue queue = new JdbcTaskQueue(conn);
            queue.initialize();

            // enqueue round-trips PENDING with payload intact
            JdbcTaskQueue.TaskRow t1 = queue.enqueue(null, "demo-flow",
                    TaskPriority.NORMAL, "{\"input\": 1}");
            assertEquals(TaskStatus.PENDING, t1.status());
            assertEquals("{\"input\": 1}", t1.payload());
            assertEquals(t1.taskId(), queue.get(t1.taskId()).orElseThrow().taskId());

            // FIFO claim single winner: first enqueued claims first, RUNNING
            JdbcTaskQueue.TaskRow t2 = queue.enqueue(null, "flow", TaskPriority.NORMAL, "p2");
            JdbcTaskQueue.TaskRow c1 = queue.claimNext("worker-a");
            JdbcTaskQueue.TaskRow c2 = queue.claimNext("worker-b");
            assertNotNull(c1);
            assertNotNull(c2);
            assertTrue(!c1.taskId().equals(c2.taskId()),
                    "two claims must yield two distinct tasks");
            assertEquals(TaskStatus.RUNNING, c1.status());
            assertEquals(t1.taskId(), c1.taskId(), "FIFO: the first enqueued claims first");

            // queue drained: a third claim has nothing left
            assertNull(queue.claimNext("worker-c"));

            // complete closes the loop with the result persisted
            JdbcTaskQueue.TaskRow done = queue.complete(t1.taskId(), "result-42");
            assertEquals(TaskStatus.SUCCEEDED, done.status());
            assertEquals("result-42", queue.get(t1.taskId()).orElseThrow().result());
        }
    }
}

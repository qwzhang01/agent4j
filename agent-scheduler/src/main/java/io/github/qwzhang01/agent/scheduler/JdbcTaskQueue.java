package io.github.qwzhang01.agent.scheduler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JDBC-backed persistent task table "Scheduler uses an
 * external queue or a persistent task table". Tasks survive process
 * crashes and are shareable across runtime instances: one row per {@link
 * AsyncTask}, claim by guarded UPDATE (PENDING→RUNNING under this
 * worker), terminal completion, explicit cancel.
 * <p>
 * {@code input}/{@code result} are stored as caller-provided strings —
 * the table stores opaque payloads, serialization belongs to the host
 * (same policy as {@code A2ATaskStore}'s serializedState). Priority
 * ordering uses the enum's weight column, FIFO by {@code seq} (an
 * autoincrementing identity, assigned at enqueue time).
 * <p>
 * Portable ANSI SQL (H2 tests / PostgreSQL production); claim contention
 * is a guarded UPDATE, not {@code FOR UPDATE SKIP LOCKED}.
 */
public final class JdbcTaskQueue {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_tasks (
                task_id        VARCHAR(128) PRIMARY KEY,
                parent_run_id  VARCHAR(128),
                workflow_name  VARCHAR(256),
                priority       INT          NOT NULL,
                priority_name  VARCHAR(32)  NOT NULL,
                status         VARCHAR(32)  NOT NULL,
                payload        TEXT,
                result         TEXT,
                seq            BIGINT       NOT NULL,
                created_at   BIGINT       NOT NULL,
                started_at   BIGINT,
                completed_at BIGINT,
                heartbeat_at BIGINT,
                tenant_id    VARCHAR(128)
            )
            """;

    /** A row in the persistent task table (DB-shaped AsyncTask mirror). */
    public record TaskRow(
            String taskId,
            String parentRunId,
            String workflowName,
            TaskPriority priority,
            TaskStatus status,
            String payload,
            String result,
            long seq,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            String tenantId) {

        /** Legacy 11-field shape (pre-0.1.4 rows): tenant stays null. */
        public TaskRow(String taskId, String parentRunId, String workflowName,
                       TaskPriority priority, TaskStatus status, String payload,
                       String result, long seq, Instant createdAt, Instant startedAt,
                       Instant completedAt) {
            this(taskId, parentRunId, workflowName, priority, status, payload,
                    result, seq, createdAt, startedAt, completedAt, null);
        }

        public TaskRow withStatus(TaskStatus next) {
            return new TaskRow(taskId, parentRunId, workflowName, priority, next, payload,
                    result, seq, createdAt,
                    next == TaskStatus.RUNNING ? Instant.now() : startedAt,
                    next.isTerminal() ? Instant.now() : completedAt, tenantId);
        }

        public TaskRow withResult(String result) {
            return new TaskRow(taskId, parentRunId, workflowName, priority,
                    TaskStatus.SUCCEEDED, payload, result, seq, createdAt, startedAt,
                    Instant.now(), tenantId);
        }
    }

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private final ConnectionSupplier connections;
    private final boolean ownsConnections;
    private final int capacity;
    private final java.util.concurrent.atomic.AtomicInteger totalRejected =
            new java.util.concurrent.atomic.AtomicInteger();

    public JdbcTaskQueue(ConnectionSupplier connections) {
        this(connections, 0);
    }

    /**
     * Harness 8.1 (2026-09-17): bounded capacity. {@code capacity <= 0}
     * keeps the legacy unbounded behavior (an honest default — the queue
     * table has no inherent limit); {@code capacity > 0} makes enqueue a
     * guarded refusal: at capacity, enqueue throws {@link QueueFullException}
     * (same classified backpressure signal as {@code AsyncTaskQueue}), it
     * never silently drops or grows unbounded.
     */
    public JdbcTaskQueue(ConnectionSupplier connections, int capacity) {
        this.connections = Objects.requireNonNull(connections);
        this.ownsConnections = true;
        this.capacity = capacity;
    }

    public int capacity() {
        return capacity;
    }

    /** Tasks refused by the capacity guard (observable backpressure metric). */
    public int totalRejected() {
        return totalRejected.get();
    }

    public JdbcTaskQueue(Connection connection) {
        this.connections = () -> connection;
        this.ownsConnections = false;
        this.capacity = 0;
    }

    /**
     * Count non-terminal rows (PENDING + RUNNING) — the occupancy the
     * capacity guard meters. Terminal rows (SUCCEEDED/FAILED/CANCELLED)
     * are history, not load. Public for monitoring: occupancy vs capacity
     * is the queue-health pair operators watch.
     */
    public int activeCount() {
        String sql = "SELECT COUNT(*) FROM agent4j_tasks WHERE status IN ('PENDING', 'RUNNING')";
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Task count failed: " + e.getMessage(), e);
        }
    }

    /**
     * Borrows a connection for one operation: supplier-provided
     * connections are released on close, a shared one is handed back
     * untouched.
     */
    private CloseGuard guard() throws SQLException {
        return new CloseGuard(connections.get(), ownsConnections);
    }

    /** Close only what the queue owns. */
    private static final class CloseGuard implements AutoCloseable {
        private final Connection connection;
        private final boolean closeOnRelease;

        private CloseGuard(Connection connection, boolean closeOnRelease) {
            this.connection = connection;
            this.closeOnRelease = closeOnRelease;
        }

        private Connection get() {
            return connection;
        }

        @Override
        public void close() {
            if (closeOnRelease) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // best-effort release; nothing actionable here
                }
            }
        }
    }

    /** Execute the bootstrap DDL (idempotent). */
    public void initialize() {
                try (CloseGuard g = guard(); Statement st = g.get().createStatement()) {
            st.execute(DDL);
            // Sequence counter table: portable autoincrement without IDENTITY
            // dialect differences.
            st.execute("""
                    CREATE TABLE IF NOT EXISTS agent4j_task_seq (
                        name VARCHAR(32) PRIMARY KEY,
                        next_value BIGINT NOT NULL
                    )
                    """);
            try (PreparedStatement ps = g.get().prepareStatement(
                    "INSERT INTO agent4j_task_seq (name, next_value) VALUES ('task', 1)")) {
                try {
                    ps.executeUpdate();
                } catch (SQLException alreadySeeded) {
                    // seeded: fine
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Task table init failed: " + e.getMessage(), e);
        }
    }


    /** Enqueue a task row (status PENDING). Payload is an opaque host string. */
    public TaskRow enqueue(String parentRunId, String workflowName,
                           TaskPriority priority, String payload) {
        return enqueue(parentRunId, workflowName, priority, payload, null);
    }

    /**
     * Harness 8.1 (2026-09-17): tenant-tagged enqueue with the capacity
     * guard. The guard meters non-terminal occupancy (PENDING + RUNNING)
     * against the configured capacity; at capacity the enqueue is refused
     * with {@link QueueFullException} — loud, classified backpressure,
     * same signal family as {@link AsyncTaskQueue}. The tenant column is
     * the row's identity (written once); {@code listByTenant} isolates.
     */
    public TaskRow enqueue(String parentRunId, String workflowName,
                           TaskPriority priority, String payload, String tenantId) {
        if (capacity > 0 && activeCount() >= capacity) {
            totalRejected.incrementAndGet();
            throw new QueueFullException(capacity, activeCount());
        }
        String taskId = UUID.randomUUID().toString();
        long seq = nextSeq();
        long now = System.currentTimeMillis();
        String sql = """
                INSERT INTO agent4j_tasks
                    (task_id, parent_run_id, workflow_name, priority, priority_name,
                     status, payload, result, seq, created_at, started_at, completed_at, tenant_id)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?, NULL, ?, ?, NULL, NULL, ?)
                """;
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, taskId);
            ps.setString(2, parentRunId);
            ps.setString(3, workflowName);
            ps.setInt(4, priority.weight());
            ps.setString(5, priority.name());
            ps.setString(6, payload);
            ps.setLong(7, seq);
            ps.setLong(8, now);
            ps.setString(9, tenantId);
            ps.executeUpdate();
            return new TaskRow(taskId, parentRunId, workflowName, priority,
                    TaskStatus.PENDING, payload, null, seq,
                    Instant.ofEpochMilli(now), null, null, tenantId);
        } catch (SQLException e) {
            throw new IllegalStateException("Task enqueue failed: " + e.getMessage(), e);
        }
    }

    public static final class QueueFullException extends RuntimeException {
        public QueueFullException(int capacity, int size) {
            super("[QUEUE_FULL] Persistent task table at capacity " + capacity
                    + " (active " + size + ") - reject and backpressure");
        }
    }

    /** Non-terminal rows for one tenant (isolation boundary for sweeps). */
    public List<TaskRow> listByTenant(String tenantId) {
        String sql = selectAll() + " WHERE tenant_id = ? AND status IN ('PENDING', 'RUNNING') "
                + "ORDER BY seq";
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(fromRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Task listByTenant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Claim the next PENDING task (priority desc, seq asc) for this
     * worker. The claim is a guarded UPDATE (PENDING→RUNING under this
     * taskId) so two polling workers can never claim the same row — the
     * loser sees the row already RUNNING and retries the next one.
     *
     * @return the claimed row, or {@code null} when no PENDING task exists
     */
    public TaskRow claimNext(String workerId) {
        // Two-step claim without SKIP LOCKED: select candidate ids, then
        // CAS one at a time until a claim lands. Both steps are cheap
        // (bounded candidate list); the guarded UPDATE is the arbiter.
        String candidates = """
                SELECT task_id FROM agent4j_tasks
                WHERE status = 'PENDING'
                ORDER BY priority DESC, seq ASC
                """;
        // The claim stamps the lease clock (heartbeat_at) together with
        // started_at — an unattended task (holder never renews) is judged
        // by claim time via COALESCE(heartbeat_at, started_at), so legacy
        // claim-only behavior is preserved byte-for-byte.
        String claim = """
                UPDATE agent4j_tasks
                SET status = 'RUNNING', started_at = ?, heartbeat_at = ?
                WHERE task_id = ? AND status = 'PENDING'
                """;
        try (CloseGuard g = guard()) {
            Connection c = g.get();
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(candidates);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next() && ids.size() < 16) {
                    ids.add(rs.getString(1));
                }
            }
            for (String id : ids) {
                try (PreparedStatement ps = c.prepareStatement(claim)) {
                    long now = System.currentTimeMillis();
                    ps.setLong(1, now);
                    ps.setLong(2, now);
                    ps.setString(3, id);
                    if (ps.executeUpdate() == 1) {
                        return get(id).orElseThrow();
                    }
                }
            }
            return null;
        } catch (SQLException e) {
            throw new IllegalStateException("Task claim failed: " + e.getMessage(), e);
        }
    }

    /** Mark a claimed task SUCCEEDED with its result. */
    public TaskRow complete(String taskId, String result) {
        String sql = """
                UPDATE agent4j_tasks
                SET status = 'SUCCEEDED', result = ?, completed_at = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """;
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, result);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, taskId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                        "Task '" + taskId + "' cannot complete: not RUNNING (already terminal?)");
            }
            return get(taskId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Task complete failed: " + e.getMessage(), e);
        }
    }

    /** Mark a claimed task FAILED (terminal). */
    public TaskRow fail(String taskId) {
        String sql = """
                UPDATE agent4j_tasks
                SET status = 'FAILED', completed_at = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """;
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, taskId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                        "Task '" + taskId + "' cannot fail: not RUNNING (already terminal?)");
            }
            return get(taskId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Task fail failed: " + e.getMessage(), e);
        }
    }

    /** Cancel a PENDING task (terminal). */
    public TaskRow cancel(String taskId) {
        String sql = """
                UPDATE agent4j_tasks
                SET status = 'CANCELLED', completed_at = ?
                WHERE task_id = ? AND status = 'PENDING'
                """;
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, taskId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalStateException(
                        "Task '" + taskId + "' cannot cancel: not PENDING");
            }
            return get(taskId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Task cancel failed: " + e.getMessage(), e);
        }
    }


    /**
     * Renew the lease of a claimed (RUNNING) task: stamp the lease clock
     * ({@code heartbeat_at}) now. Returns {@code true} when the row was
     * RUNNING and got renewed; {@code false} when the row is not RUNNING
     * (never claimed, completed, cancelled, or already requeued by a
     * sweep) — a holder seeing {@code false} no longer owns the task and
     * should stop working on it (fail-closed on ownership; see {@link
     * ClaimedTaskHeartbeat} for the auto-renewing holder). Store errors
     * still throw — the caller decides whether to fail open (retry next
     * interval) or give up; a throw is never a renewal.
     */
    public boolean heartbeat(String taskId) {
        String sql = """
                UPDATE agent4j_tasks
                SET heartbeat_at = ?
                WHERE task_id = ? AND status = 'RUNNING'
                """;
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, taskId);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Task heartbeat failed: " + e.getMessage(), e);
        }
    }

    /**
     * The task's lease clock (last holder renewal; claim time when never
     * renewed), for monitoring and tests. Empty when the row does not
     * exist.
     */
    public java.util.Optional<Instant> heartbeatAt(String taskId) {
        String sql = "SELECT COALESCE(heartbeat_at, started_at) FROM agent4j_tasks "
                + "WHERE task_id = ?";
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getLong(1) > 0
                        ? java.util.Optional.of(Instant.ofEpochMilli(rs.getLong(1)))
                        : java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Task heartbeatAt failed: " + e.getMessage(), e);
        }
    }

    /**
     * After a worker crash: RUNNING rows are orphans (their holder died).
     * Requeue them to PENDING so another worker can claim. A live worker
     * keeps its lease via the task's own completion — requeueing a row
     * still actively RUNNING on a live instance is the host's call; the
     * default policy here only requeues rows whose lease clock — {@code
     * COALESCE(heartbeat_at, started_at)}: the last holder renewal, or
     * claim time when the holder never renewed — is older than the given
     * grace window (a live worker renews faster than grace; a crashed
     * one never renews or finishes). Harness batch 5 moved liveness off
     * claim-time-only: a task running longer than grace stays claimed as
     * long as its holder heartbeats (see {@link ClaimedTaskHeartbeat}).
     */
    public List<String> requeueOrphaned(long graceMillis) {
        // Snapshot the orphan ids first, then requeue exactly those rows via
        // guarded UPDATE. The guard (status still RUNNING) means a task that
        // completed between snapshot and sweep is NOT requeued — and the
        // returned list contains only rows actually requeued, never freshly
        // enqueued PENDING tasks that were never RUNNING.
        long cutoff = System.currentTimeMillis() - graceMillis;
        String find = """
                SELECT task_id FROM agent4j_tasks
                WHERE status = 'RUNNING' AND COALESCE(heartbeat_at, started_at) <= ?
                """;
        // The requeue resets the lease clock too: the next claim starts a
        // fresh lease instead of inheriting the dead holder's aged one.
        String requeue = """
                UPDATE agent4j_tasks
                SET status = 'PENDING', started_at = NULL, heartbeat_at = NULL
                WHERE task_id = ? AND status = 'RUNNING'
                """;
        try (CloseGuard g = guard()) {
            Connection c = g.get();
            List<String> orphans = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(find)) {
                ps.setLong(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        orphans.add(rs.getString(1));
                    }
                }
            }
            List<String> requeued = new ArrayList<>();
            for (String id : orphans) {
                try (PreparedStatement ps = c.prepareStatement(requeue)) {
                    ps.setString(1, id);
                    if (ps.executeUpdate() == 1) {
                        requeued.add(id);
                    }
                }
            }
            return requeued;
        } catch (SQLException e) {
            throw new IllegalStateException("Task requeue sweep failed: " + e.getMessage(), e);
        }
    }


    public java.util.Optional<TaskRow> get(String taskId) {
        String sql = selectAll() + " WHERE task_id = ?";
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? java.util.Optional.of(fromRow(rs)) : java.util.Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Task get failed: " + e.getMessage(), e);
        }
    }

    public List<TaskRow> listByStatus(TaskStatus status) {
        String sql = selectAll() + " WHERE status = ? ORDER BY seq";
                try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, status.name());
            try (ResultSet rs = ps.executeQuery()) {
                List<TaskRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(fromRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Task listByStatus failed: " + e.getMessage(), e);
        }
    }

    public List<TaskRow> listPending() {
        return listByStatus(TaskStatus.PENDING);
    }

    public int countByStatus(TaskStatus status) {
        return listByStatus(status).size();
    }


    private long nextSeq() {
        // Portable increment without IDENTITY: bump the counter row and
        // read it back. Single-connection usage (embedded H2 / serialized
        // schedulers) makes this race-free; a pooled production datasource
        // should swap in a real sequence.
        String bump = "UPDATE agent4j_task_seq SET next_value = next_value + 1 WHERE name = 'task'";
        String read = "SELECT next_value FROM agent4j_task_seq WHERE name = 'task'";
        try (CloseGuard g = guard(); Statement st = g.get().createStatement()) {
            st.executeUpdate(bump);
            try (ResultSet rs = st.executeQuery(read)) {
                rs.next();
                return rs.getLong(1) - 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("task seq failed: " + e.getMessage(), e);
        }
    }

    private static String selectAll() {
        return "SELECT task_id, parent_run_id, workflow_name, priority, priority_name, "
                + "status, payload, result, seq, created_at, started_at, completed_at, tenant_id "
                + "FROM agent4j_tasks";
    }

    private static TaskRow fromRow(ResultSet rs) throws SQLException {
        return new TaskRow(
                rs.getString("task_id"),
                rs.getString("parent_run_id"),
                rs.getString("workflow_name"),
                TaskPriority.valueOf(rs.getString("priority_name")),
                TaskStatus.valueOf(rs.getString("status")),
                rs.getString("payload"),
                rs.getString("result"),
                rs.getLong("seq"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                rs.getLong("started_at") == 0 ? null : Instant.ofEpochMilli(rs.getLong("started_at")),
                rs.getLong("completed_at") == 0 ? null : Instant.ofEpochMilli(rs.getLong("completed_at")),
                rs.getString("tenant_id"));
    }
}

package io.github.qwzhang01.agent.workflow.runtime.durable;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.workflow.StepRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed {@link RunStore} (Stage 8.1, harness roadmap): the database
 * is the source of truth, not any JVM's memory. Two runtime instances
 * pointing at the same schema see the same runs; the optimistic {@code
 * version} column makes concurrent transitions lose loudly, not silently.
 * <p>
 * Dialect policy: plain ANSI SQL that H2 (tests) and PostgreSQL
 * (production) both accept. No {@code MERGE}, no {@code ON CONFLICT}, no
 * {@code FOR UPDATE SKIP LOCKED} here — portability beats cleverness;
 * lease/queue contention uses the {@code version} CAS instead.
 * <p>
 * {@code lastTrace} (a list of {@link StepRecord}s) is serialized to JSON
 * via Jackson; the record shape is part of the row contract and
 * round-trips through {@link TraceCodec}.
 * <p>
 * Schema bootstrap is explicit ({@link #initialize()}), not an implicit
 * startup mutation — the roadmap's 3.1 rule: "design a migration, do not
 * alter tables silently at boot". DDL is {@code CREATE TABLE IF NOT
 * EXISTS} so repeated calls are safe; production deployments that prefer
 * Flyway/Liquibase can run the DDL from {@link #ddl()} themselves and
 * skip initialize().
 */
public final class JdbcRunStore implements RunStore {

    /** ANSI DDL accepted by both H2 and PostgreSQL. */
    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_runs (
                run_id            VARCHAR(128)  PRIMARY KEY,
                workflow_name     VARCHAR(256)  NOT NULL,
                workflow_version  VARCHAR(64)   NOT NULL,
                workflow_hash     VARCHAR(128)  NOT NULL,
                status            VARCHAR(32)   NOT NULL,
                cursor            VARCHAR(256),
                steps_executed    INT           NOT NULL,
                last_event_seq    BIGINT        NOT NULL,
                checkpoint_id     VARCHAR(128),
                error_message     TEXT,
                created_at        BIGINT        NOT NULL,
                updated_at        BIGINT        NOT NULL,
                version           BIGINT        NOT NULL,
                last_trace        TEXT           NOT NULL,
                tenant_id         VARCHAR(128),
                versions          TEXT           NOT NULL
            )
            """;

    private final ConnectionSupplier connections;
    private final boolean ownsConnections;
    private final ObjectMapper mapper;

    /**
     * Provides a fresh (auto-commit) connection per operation; the store
     * releases (closes) it when the operation ends.
     */
    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    public JdbcRunStore(ConnectionSupplier connections) {
        this.connections = connections;
        this.ownsConnections = true;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Convenience: single shared connection (tests, embedded H2). The
     * store borrows it per operation but never closes it — the caller
     * owns that connection's lifecycle.
     */
    public JdbcRunStore(Connection connection) {
        this.connections = () -> connection;
        this.ownsConnections = false;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Borrows a connection for one operation. The guard is declared as
     * the FIRST try-with-resources resource (the connection is fetched
     * through {@code g.get()}, never declared as a resource itself), so
     * the JVM never auto-closes the connection — only the guard decides:
     * supplier-provided connections are released, a shared one is handed
     * back untouched.
     */
    private CloseGuard guard() throws SQLException {
        return new CloseGuard(connections.get(), ownsConnections);
    }

    /** Close only what the store owns. */
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
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore schema init failed: " + e.getMessage(), e);
        }
    }

    /** The DDL string, for hosts that manage migrations externally. */
    public static String ddl() {
        return DDL;
    }

    @Override
    public RunRecord create(RunRecord record) {
        String sql = """
                INSERT INTO agent4j_runs
                    (run_id, workflow_name, workflow_version, workflow_hash, status,
                     cursor, steps_executed, last_event_seq, checkpoint_id, error_message,
                     created_at, updated_at, version, last_trace, tenant_id, versions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, record.runId());
            ps.setString(2, nvl(record.workflowName()));
            ps.setString(3, nvl(record.workflowVersion()));
            ps.setString(4, nvl(record.workflowHash()));
            ps.setString(5, record.status());
            ps.setString(6, record.cursor());
            ps.setInt(7, record.stepsExecuted());
            ps.setLong(8, record.lastEventSeq());
            ps.setString(9, record.checkpointId());
            ps.setString(10, record.errorMessage());
            ps.setLong(11, record.createdAt());
            ps.setLong(12, record.updatedAt());
            ps.setLong(13, record.version());
            ps.setString(14, TraceCodec.write(mapper, record.lastTrace()));
            ps.setString(15, record.tenantId());
            ps.setString(16, nvl(record.versions()));
            try {
                ps.executeUpdate();
            } catch (SQLException dup) {
                // Duplicate runId: same loud failure as the in-memory store.
                throw new VersionConflictException(
                        "Run '" + record.runId() + "' already exists", record);
            }
            return record;
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore create failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RunRecord update(RunRecord record) throws VersionConflictException {
        String sql = """
                UPDATE agent4j_runs SET
                    status = ?, cursor = ?, steps_executed = ?, last_event_seq = ?,
                    checkpoint_id = ?, error_message = ?, updated_at = ?, version = version + 1,
                    last_trace = ?
                WHERE run_id = ? AND version = ?
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, record.status());
            ps.setString(2, record.cursor());
            ps.setInt(3, record.stepsExecuted());
            ps.setLong(4, record.lastEventSeq());
            ps.setString(5, record.checkpointId());
            ps.setString(6, record.errorMessage());
            ps.setLong(7, System.currentTimeMillis());
            ps.setString(8, TraceCodec.write(mapper, record.lastTrace()));
            ps.setString(9, record.runId());
            ps.setLong(10, record.version());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                // Either missing row or stale version: both lose loudly.
                RunRecord stored = get(record.runId()).orElse(null);
                throw new VersionConflictException(
                        "Version conflict on run '" + record.runId()
                                + "': carried " + record.version()
                                + (stored == null ? ", row not found" : ", stored " + stored.version()),
                        stored);
            }
            return get(record.runId()).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore update failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunRecord> get(String runId) {
        String sql = """
                SELECT run_id, workflow_name, workflow_version, workflow_hash, status,
                       cursor, steps_executed, last_event_seq, checkpoint_id, error_message,
                       created_at, updated_at, version, last_trace, tenant_id, versions
                FROM agent4j_runs WHERE run_id = ?
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore get failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RunRecord> listRecoveryCandidates() {
        String sql = """
                SELECT run_id, workflow_name, workflow_version, workflow_hash, status,
                       cursor, steps_executed, last_event_seq, checkpoint_id, error_message,
                       created_at, updated_at, version, last_trace, tenant_id, versions
                FROM agent4j_runs
                WHERE status IN ('RUNNING', 'PAUSED', 'WAITING_APPROVAL')
                ORDER BY created_at
                """;
        return queryList(sql);
    }

    @Override
    public List<RunRecord> listByStatus(String status) {
        String sql = """
                SELECT run_id, workflow_name, workflow_version, workflow_hash, status,
                       cursor, steps_executed, last_event_seq, checkpoint_id, error_message,
                       created_at, updated_at, version, last_trace, tenant_id, versions
                FROM agent4j_runs WHERE status = ? ORDER BY created_at
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, status);
            try (ResultSet rs = ps.executeQuery()) {
                List<RunRecord> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(fromRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore listByStatus failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<RunRecord> listAll() {
        return queryList("""
                SELECT run_id, workflow_name, workflow_version, workflow_hash, status,
                       cursor, steps_executed, last_event_seq, checkpoint_id, error_message,
                       created_at, updated_at, version, last_trace, tenant_id, versions
                FROM agent4j_runs ORDER BY created_at
                """);
    }

    private List<RunRecord> queryList(String sql) {
        try (CloseGuard g = guard();
             PreparedStatement ps = g.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<RunRecord> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(fromRow(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("RunStore query failed: " + e.getMessage(), e);
        }
    }

    private RunRecord fromRow(ResultSet rs) throws SQLException {
        List<StepRecord> trace = TraceCodec.read(mapper, rs.getString("last_trace"));
        return new RunRecord(
                rs.getString("run_id"),
                rs.getString("workflow_name"),
                rs.getString("workflow_version"),
                rs.getString("workflow_hash"),
                rs.getString("status"),
                rs.getString("cursor"),
                rs.getInt("steps_executed"),
                rs.getLong("last_event_seq"),
                rs.getString("checkpoint_id"),
                rs.getString("error_message"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getLong("version"),
                trace,
                rs.getString("tenant_id"),
                rs.getString("versions") == null ? "" : rs.getString("versions"));
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** JSON round-trip for the {@code lastTrace} column (shared with the ledger). */
    static final class TraceCodec {
        private TraceCodec() {
        }

        static String write(ObjectMapper mapper, List<StepRecord> trace) {
            try {
                return mapper.writeValueAsString(trace == null ? List.of() : trace);
            } catch (Exception e) {
                throw new IllegalStateException("trace serialize failed", e);
            }
        }

        static List<StepRecord> read(ObjectMapper mapper, String json) {
            if (json == null || json.isBlank()) {
                return List.of();
            }
            try {
                return mapper.readValue(json, new TypeReference<List<StepRecord>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("trace deserialize failed", e);
            }
        }
    }
}

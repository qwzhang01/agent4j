package io.github.qwzhang01.agent.workflow.runtime.durable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.workflow.runtime.Checkpoint;
import io.github.qwzhang01.agent.workflow.runtime.CheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.FileCheckpointStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed {@link CheckpointStore} (Stage 8.1, harness roadmap): the
 * checkpoint payload rides in the database so instance A's pause is
 * instance B's resume. Without a shared checkpoint store, "two runtime
 * instances can safely take over the same waiting run" (Stage 8's
 * completion definition) is impossible — B has no blackboard to restore.
 * <p>
 * Reuses {@link FileCheckpointStore.Snapshot} as the JSON codec (one
 * codec, two transports): schema-version refusal, legacy identity
 * fallback, and {@code WorkflowState.restore} semantics stay identical
 * whether the payload came from disk or a database.
 * <p>
 * Dialect policy: plain ANSI SQL (H2 tests / PostgreSQL production), no
 * {@code MERGE}, no {@code ON CONFLICT}. Upsert discipline is the same
 * CAS-by-affected-rows pattern the other Stage 8.1 JDBC stores use:
 * INSERT first; on duplicate key fall through to a guarded UPDATE.
 * <p>
 * runId validation is inherited from {@link FileCheckpointStore#validateRunId}
 * so a hostile runId is rejected before it reaches SQL.
 */
public final class JdbcCheckpointStore implements CheckpointStore {

    /** ANSI DDL accepted by both H2 and PostgreSQL. */
    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_checkpoints (
                run_id       VARCHAR(128) PRIMARY KEY,
                checkpoint_id VARCHAR(128) NOT NULL,
                schema_version INT         NOT NULL,
                payload      CLOB          NOT NULL
            )
            """;

    private final JdbcRunStore.ConnectionSupplier connections;
    private final boolean ownsConnections;
    private final ObjectMapper mapper;

    public JdbcCheckpointStore(JdbcRunStore.ConnectionSupplier connections) {
        this.connections = connections;
        this.ownsConnections = true;
        this.mapper = new ObjectMapper().findAndRegisterModules();
    }

    /**
     * Convenience: single shared connection (tests, embedded H2). The
     * store borrows it per operation but never closes it — the caller
     * owns that connection's lifecycle.
     */
    public JdbcCheckpointStore(Connection connection) {
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
            throw new IllegalStateException("Checkpoint schema init failed: " + e.getMessage(), e);
        }
    }

    /** The DDL string, for hosts that manage migrations externally. */
    public static String ddl() {
        return DDL;
    }

    // ============ CheckpointStore ============

    @Override
    public String save(Checkpoint checkpoint) {
        FileCheckpointStore.validateRunId(checkpoint.runId());
        String sql = """
                INSERT INTO agent4j_checkpoints (run_id, checkpoint_id, schema_version, payload)
                VALUES (?, ?, ?, ?)
                """;
        String payload = writePayload(checkpoint);
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, checkpoint.runId());
            ps.setString(2, checkpoint.checkpointId());
            ps.setInt(3, checkpoint.schemaVersion());
            ps.setString(4, payload);
            try {
                ps.executeUpdate();
            } catch (SQLException dup) {
                // Duplicate runId: fall through to the guarded update below.
                return updateExisting(checkpoint);
            }
            return checkpoint.checkpointId();
        } catch (SQLException e) {
            throw new IllegalStateException("Checkpoint save failed: " + e.getMessage(), e);
        }
    }

    /** UPDATE branch of the upsert: one latest checkpoint per runId. */
    private String updateExisting(Checkpoint checkpoint) throws SQLException {
        String sql = """
                UPDATE agent4j_checkpoints
                SET checkpoint_id = ?, schema_version = ?, payload = ?
                WHERE run_id = ?
                """;
        String payload = writePayload(checkpoint);
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, checkpoint.checkpointId());
            ps.setInt(2, checkpoint.schemaVersion());
            ps.setString(3, payload);
            ps.setString(4, checkpoint.runId());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new IllegalStateException(
                        "Checkpoint save lost the race for '" + checkpoint.runId() + "'");
            }
            return checkpoint.checkpointId();
        }
    }

    /** Serialize once; a payload that cannot be written is a store bug, not a store policy. */
    private String writePayload(Checkpoint checkpoint) {
        try {
            return mapper.writeValueAsString(
                    FileCheckpointStore.Snapshot.from(checkpoint));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Checkpoint payload not serializable for '"
                    + checkpoint.runId() + "': " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Checkpoint> load(String runId) {
        FileCheckpointStore.validateRunId(runId);
        String sql = "SELECT payload FROM agent4j_checkpoints WHERE run_id = ?";
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String payload = rs.getString(1);
                FileCheckpointStore.Snapshot snap = mapper.readValue(
                        payload, FileCheckpointStore.Snapshot.class);
                return Optional.of(snap.toCheckpoint());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Checkpoint load failed: " + e.getMessage(), e);
        } catch (com.fasterxml.jackson.core.JsonProcessingException jpe) {
            throw new IllegalStateException("Checkpoint payload corrupt for '" + runId + "': "
                    + jpe.getMessage(), jpe);
        }
    }

    @Override
    public void delete(String runId) {
        FileCheckpointStore.validateRunId(runId);
        String sql = "DELETE FROM agent4j_checkpoints WHERE run_id = ?";
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Checkpoint delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> listRunIds() {
        String sql = "SELECT run_id FROM agent4j_checkpoints ORDER BY run_id";
        try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<String> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getString(1));
            }
            return List.copyOf(ids);
        } catch (SQLException e) {
            throw new IllegalStateException("Checkpoint list failed: " + e.getMessage(), e);
        }
    }
}

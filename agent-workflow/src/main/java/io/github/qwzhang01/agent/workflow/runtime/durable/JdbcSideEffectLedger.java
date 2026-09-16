package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC-backed {@link SideEffectLedger} (Stage 8.1): the write-ahead truth
 * for external side effects lands in the database, shared across runtime
 * instances. {@code INSERT} on the {@code PRIMARY KEY (effect_id)} gives
 * the idempotent-record semantic for free: a duplicate record of the same
 * effect is rejected by the constraint and the original is returned —
 * exactly the in-memory {@code putIfAbsent} contract, no dialect tricks.
 * <p>
 * Same dialect policy as {@link JdbcRunStore}: portable ANSI SQL only.
 */
public final class JdbcSideEffectLedger implements SideEffectLedger {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_effects (
                effect_id        VARCHAR(512)  PRIMARY KEY,
                run_id           VARCHAR(128)  NOT NULL,
                node_id          VARCHAR(256)  NOT NULL,
                idempotency_key  VARCHAR(512),
                args_hash        VARCHAR(128),
                semantics        VARCHAR(32)   NOT NULL,
                disposition      VARCHAR(32)   NOT NULL,
                result           TEXT,
                completed_at     BIGINT        NOT NULL
            )
            """;

    private final JdbcRunStore.ConnectionSupplier connections;
    private final boolean ownsConnections;

    public JdbcSideEffectLedger(JdbcRunStore.ConnectionSupplier connections) {
        this.connections = connections;
        this.ownsConnections = true;
    }

    /**
     * Convenience: single shared connection (tests, embedded H2). The
     * store borrows it per operation but never closes it — the caller
     * owns that connection's lifecycle.
     */
    public JdbcSideEffectLedger(Connection connection) {
        this.connections = () -> connection;
        this.ownsConnections = false;
    }

    /**
     * Borrows a connection for one operation: supplier-provided
     * connections are released on close, a shared one is handed back
     * untouched.
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
            throw new IllegalStateException("Effect ledger schema init failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Effect record(Effect effect) {
        String insert = """
                INSERT INTO agent4j_effects
                    (effect_id, run_id, node_id, idempotency_key, args_hash,
                     semantics, disposition, result, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(insert)) {
            ps.setString(1, effect.effectId());
            ps.setString(2, effect.runId());
            ps.setString(3, effect.nodeId());
            ps.setString(4, effect.idempotencyKey());
            ps.setString(5, effect.argsHash());
            ps.setString(6, effect.semantics().name());
            ps.setString(7, effect.disposition().name());
            ps.setString(8, effect.result());
            ps.setLong(9, effect.completedAt());
            try {
                ps.executeUpdate();
                return effect;
            } catch (SQLException dup) {
                // PK collision = duplicate record: idempotent no-op, original wins.
                return lookup(effect.runId(), effect.nodeId(),
                        effect.argsHash() == null || effect.argsHash().isEmpty()
                                ? null : effect.argsHash())
                        .orElseThrow(() -> new IllegalStateException(
                                "Effect insert collided but row vanished: " + effect.effectId()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Effect record failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<Effect> lookup(String runId, String nodeId) {
        return lookupByWhere("run_id = ? AND node_id = ?", runId, nodeId, null);
    }

    @Override
    public Optional<Effect> lookup(String runId, String nodeId, String callHash) {
        return lookupByWhere("run_id = ? AND node_id = ? AND args_hash = ?",
                runId, nodeId, callHash);
    }

    private Optional<Effect> lookupByWhere(String where, String runId, String nodeId,
                                           String callHash) {
        // H2/ANSI semantics: "= NULL" never matches. A node-scoped lookup
        // (callHash null) must therefore use its own WHERE shape with only
        // two placeholders — reusing the three-placeholder SQL would leave
        // parameter #3 unset and break.
        boolean callScoped = callHash != null;
        String sql = "SELECT effect_id, run_id, node_id, idempotency_key, args_hash, "
                + "semantics, disposition, result, completed_at FROM agent4j_effects WHERE "
                + (callScoped ? where : "run_id = ? AND node_id = ? "
                        + "AND (args_hash IS NULL OR args_hash = '')");
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, nodeId);
            if (callScoped) {
                ps.setString(3, callHash);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Effect lookup failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Effect> effectsForRun(String runId) {
        String sql = "SELECT effect_id, run_id, node_id, idempotency_key, args_hash, "
                + "semantics, disposition, result, completed_at FROM agent4j_effects "
                + "WHERE run_id = ? ORDER BY completed_at";
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Effect> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(fromRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Effect listForRun failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Effect> allEffects() {
        String sql = "SELECT effect_id, run_id, node_id, idempotency_key, args_hash, "
                + "semantics, disposition, result, completed_at FROM agent4j_effects "
                + "ORDER BY completed_at";
        try (CloseGuard g = guard();
             PreparedStatement ps = g.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Effect> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(fromRow(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Effect listAll failed: " + e.getMessage(), e);
        }
    }

    private static Effect fromRow(ResultSet rs) throws SQLException {
        return new Effect(
                rs.getString("effect_id"),
                rs.getString("run_id"),
                rs.getString("node_id"),
                orEmpty(rs.getString("idempotency_key")),
                orEmpty(rs.getString("args_hash")),
                DeliverySemantics.valueOf(rs.getString("semantics")),
                RetryDisposition.valueOf(rs.getString("disposition")),
                rs.getString("result"),
                rs.getLong("completed_at"));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}

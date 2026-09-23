package io.github.qwzhang01.agent.workflow.runtime.durable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;

/**
 * JDBC-backed {@link RunLeases} : lease rows live in the
 * database so two runtime instances can coordinate ownership of the same
 * run. CAS discipline on the {@code version} column reproduces the
 * in-memory registry's rules exactly:
 * <ol>
 *   <li>single winner: acquire CASes holder NULL→worker (or expired→worker)</li>
 *   <li>TTL: expired rows are take-overable</li>
 *   <li>heartbeat: renew CASes holder=me AND not-expired → extend</li>
 *   <li>holder-only release</li>
 * </ol>
 * Portable ANSI SQL; no {@code FOR UPDATE} needed — the guarded UPDATE's
 * affected-row count is the CAS result.
 */
public final class JdbcRunLeases implements RunLeases {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_run_leases (
                run_id      VARCHAR(128) PRIMARY KEY,
                holder      VARCHAR(256) NOT NULL,
                acquired_at BIGINT       NOT NULL,
                expires_at  BIGINT       NOT NULL
            )
            """;

    private final JdbcRunStore.ConnectionSupplier connections;
    private final boolean ownsConnections;

    public JdbcRunLeases(JdbcRunStore.ConnectionSupplier connections) {
        this.connections = connections;
        this.ownsConnections = true;
    }

    /**
     * Convenience: single shared connection (tests, embedded H2). The
     * store borrows it per operation but never closes it — the caller
     * owns that connection's lifecycle.
     */
    public JdbcRunLeases(Connection connection) {
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
            throw new IllegalStateException("Run lease schema init failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean tryAcquire(String runId, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        long expiresAt = ttlMillis <= 0 ? 0 : now + ttlMillis;
        // CAS: insert when free, or take over when expired. Single statement,
        // the affected-row count decides.
        String insert = """
                INSERT INTO agent4j_run_leases (run_id, holder, acquired_at, expires_at)
                VALUES (?, ?, ?, ?)
                """;
        String takeover = """
                UPDATE agent4j_run_leases SET holder = ?, acquired_at = ?, expires_at = ?
                WHERE run_id = ? AND (expires_at > 0 AND expires_at <= ?)
                """;
        try (CloseGuard g = guard()) {
            Connection c = g.get();
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, runId);
                ps.setString(2, holder);
                ps.setLong(3, now);
                ps.setLong(4, expiresAt);
                try {
                    if (ps.executeUpdate() == 1) {
                        return true;
                    }
                } catch (SQLException alreadyPresent) {
                    // PK collision: the row exists — fall through to the
                    // takeover attempt below; a live holder loses there.
                }
            }
            try (PreparedStatement ps = c.prepareStatement(takeover)) {
                ps.setString(1, holder);
                ps.setLong(2, now);
                ps.setLong(3, expiresAt);
                ps.setString(4, runId);
                ps.setLong(5, now);
                if (ps.executeUpdate() == 1) {
                    return true;
                }
            }
            return false; // held by a live holder: second worker loses loudly
        } catch (SQLException e) {
            throw new IllegalStateException("Lease acquire failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean renew(String runId, String holder, long ttlMillis) {
        long now = System.currentTimeMillis();
        String sql = """
                UPDATE agent4j_run_leases
                SET expires_at = ?
                WHERE run_id = ? AND holder = ? AND (expires_at = 0 OR expires_at > ?)
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setLong(1, ttlMillis <= 0 ? 0 : now + ttlMillis);
            ps.setString(2, runId);
            ps.setString(3, holder);
            ps.setLong(4, now);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Lease renew failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean release(String runId, String holder) {
        String sql = "DELETE FROM agent4j_run_leases WHERE run_id = ? AND holder = ?";
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setString(2, holder);
            return ps.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IllegalStateException("Lease release failed: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHeld(String runId) {
        return holder(runId).isPresent();
    }

    @Override
    public Optional<String> holder(String runId) {
        String sql = "SELECT holder FROM agent4j_run_leases WHERE run_id = ? "
                + "AND (expires_at = 0 OR expires_at > ?)";
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Lease holder failed: " + e.getMessage(), e);
        }
    }
}

package io.github.qwzhang01.agent.core.approval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC-backed {@link ApprovalStore} : approval decisions live
 * in the database so an approval landed on instance A is visible to the
 * run resumed on instance B. Contract identical to the in-memory
 * reference — idempotent submit, optimistic decide, holder-only revoke,
 * overdue expiry — implemented as guarded UPDATEs whose affected-row
 * count is the CAS verdict.
 * <p>
 * {@code decision} lands as a compact {@code decidedBy|decidedAt|reason}
 * column (the protocol's audit fields, no JSON machinery needed).
 * Portable ANSI SQL; H2 tests, PostgreSQL production.
 */
public final class JdbcApprovalStore implements ApprovalStore {

    static final String DDL = """
            CREATE TABLE IF NOT EXISTS agent4j_approvals (
                approval_id   VARCHAR(512) PRIMARY KEY,
                run_id        VARCHAR(128) NOT NULL,
                step_id       VARCHAR(256) NOT NULL,
                tool_call_hash VARCHAR(128),
                requested_by  VARCHAR(256),
                risk_level    VARCHAR(64),
                summary       TEXT,
                expires_at    BIGINT      NOT NULL,
                created_at    BIGINT      NOT NULL,
                status        VARCHAR(32) NOT NULL,
                decided_by    VARCHAR(256),
                decided_at    BIGINT,
                reason        TEXT,
                version       BIGINT      NOT NULL
            )
            """;

    @FunctionalInterface
    public interface ConnectionSupplier {
        Connection get() throws SQLException;
    }

    private final ConnectionSupplier connections;
    private final boolean ownsConnections;

    public JdbcApprovalStore(ConnectionSupplier connections) {
        this.connections = Objects.requireNonNull(connections);
        this.ownsConnections = true;
    }

    /**
     * Convenience: single shared connection (tests, embedded H2). The
     * store borrows it per operation but never closes it — the caller
     * owns that connection's lifecycle.
     */
    public JdbcApprovalStore(Connection connection) {
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
            throw new IllegalStateException("Approval schema init failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ApprovalRequest submit(ApprovalRequest request) {
        String sql = """
                INSERT INTO agent4j_approvals
                    (approval_id, run_id, step_id, tool_call_hash, requested_by, risk_level,
                     summary, expires_at, created_at, status, decided_by, decided_at, reason, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, request.approvalId());
            ps.setString(2, request.runId());
            ps.setString(3, request.stepId());
            ps.setString(4, request.toolCallHash());
            ps.setString(5, request.requestedBy());
            ps.setString(6, request.riskLevel());
            ps.setString(7, request.summary());
            ps.setLong(8, request.expiresAt());
            ps.setLong(9, request.createdAt());
            ps.setString(10, request.status().name());
            ps.setString(11, null);
            ps.setLong(12, 0);
            ps.setString(13, null);
            ps.setLong(14, request.version());
            try {
                ps.executeUpdate();
                return request;
            } catch (SQLException dup) {
                // PK collision: idempotent submit, the original wins.
                return get(request.approvalId()).orElseThrow(() ->
                        new IllegalStateException("Approval insert collided but row vanished"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Approval submit failed: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<ApprovalRequest> get(String approvalId) {
        String sql = selectAll() + " WHERE approval_id = ?";
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, approvalId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(fromRow(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Approval get failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ApprovalRequest decide(ApprovalRequest request, ApprovalDecision decision,
                                   ApprovalStatus targetStatus) {
        // Guarded UPDATE = optimistic CAS: only lands when the stored
        // version matches the decision's base version AND the request is
        // still PENDING (decisions are one-shot).
        String sql = """
                UPDATE agent4j_approvals
                SET status = ?, decided_by = ?, decided_at = ?, reason = ?, version = version + 1
                WHERE approval_id = ? AND version = ? AND status = 'PENDING'
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, targetStatus.name());
            ps.setString(2, decision.decidedBy());
            ps.setLong(3, decision.decidedAt());
            ps.setString(4, decision.reason());
            ps.setString(5, request.approvalId());
            ps.setLong(6, decision.version());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                ApprovalRequest stored = get(request.approvalId()).orElse(null);
                if (stored == null) {
                    throw new ApprovalConflictException(
                            "Unknown approval: '" + request.approvalId() + "'", request);
                }
                if (stored.status().isTerminal()) {
                    throw new ApprovalConflictException(
                            "Approval '" + stored.approvalId() + "' already " + stored.status()
                                    + " - decisions are one-shot", stored);
                }
                throw new ApprovalConflictException(
                        "Stale decision version " + decision.version() + " for approval '"
                                + stored.approvalId() + "' (current " + stored.version() + ")", stored);
            }
            return get(request.approvalId()).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Approval decide failed: " + e.getMessage(), e);
        }
    }

    @Override
    public ApprovalRequest revoke(String approvalId, ApprovalDecision revocation) {
        String sql = """
                UPDATE agent4j_approvals
                SET status = 'REVOKED', decided_by = ?, decided_at = ?, reason = ?, version = version + 1
                WHERE approval_id = ? AND status = 'APPROVED'
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, revocation.decidedBy());
            ps.setLong(2, revocation.decidedAt());
            ps.setString(3, revocation.reason());
            ps.setString(4, approvalId);
            if (ps.executeUpdate() == 0) {
                ApprovalRequest stored = get(approvalId).orElse(null);
                if (stored == null) {
                    throw new ApprovalConflictException(
                            "Unknown approval: '" + approvalId + "'", null);
                }
                throw new ApprovalConflictException(
                        "Only APPROVED requests can be revoked; '" + approvalId
                                + "' is " + stored.status(), stored);
            }
            return get(approvalId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("Approval revoke failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> expireOverdue(long nowEpochMs) {
        // Flip overdue PENDING rows; guarded by the same conditions as the
        // reference (PENDING + past expiry).
        String sql = """
                UPDATE agent4j_approvals
                SET status = 'EXPIRED', decided_by = 'system:expiry',
                    decided_at = ?, reason = ?, version = version + 1
                WHERE status = 'PENDING' AND expires_at > 0 AND expires_at <= ?
                """;
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setLong(1, nowEpochMs);
            ps.setString(2, "expired at " + nowEpochMs);
            ps.setLong(3, nowEpochMs);
            ps.executeUpdate();
            // Report the flipped ids: selection after the update is the
            // simple portable form (no RETURNING in ANSI SQL).
            try (PreparedStatement sel = g.get().prepareStatement(
                    "SELECT approval_id FROM agent4j_approvals "
                            + "WHERE status = 'EXPIRED' AND decided_by = 'system:expiry' "
                            + "AND decided_at = ?")) {
                sel.setLong(1, nowEpochMs);
                try (ResultSet rs = sel.executeQuery()) {
                    List<String> flipped = new ArrayList<>();
                    while (rs.next()) {
                        flipped.add(rs.getString(1));
                    }
                    return flipped;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Approval expiry sweep failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ApprovalRequest> pendingForRun(String runId) {
        String sql = selectAll() + " WHERE run_id = ? AND status = 'PENDING' ORDER BY created_at";
try (CloseGuard g = guard(); PreparedStatement ps = g.get().prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<ApprovalRequest> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(fromRow(rs));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Approval pendingForRun failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ApprovalRequest> allPending() {
        String sql = selectAll() + " WHERE status = 'PENDING' ORDER BY created_at";
        try (CloseGuard g = guard();
             PreparedStatement ps = g.get().prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<ApprovalRequest> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(fromRow(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Approval allPending failed: " + e.getMessage(), e);
        }
    }

    private static String selectAll() {
        return "SELECT approval_id, run_id, step_id, tool_call_hash, requested_by, "
                + "risk_level, summary, expires_at, created_at, status, decided_by, "
                + "decided_at, reason, version FROM agent4j_approvals";
    }

    private static ApprovalRequest fromRow(ResultSet rs) throws SQLException {
        String decidedBy = rs.getString("decided_by");
        ApprovalDecision decision = decidedBy == null ? null : new ApprovalDecision(
                decidedBy, rs.getLong("decided_at"), rs.getString("reason"), rs.getLong("version") - 1);
        return new ApprovalRequest(
                rs.getString("approval_id"),
                rs.getString("run_id"),
                rs.getString("step_id"),
                rs.getString("tool_call_hash"),
                rs.getString("requested_by"),
                rs.getString("risk_level"),
                rs.getString("summary"),
                rs.getLong("expires_at"),
                rs.getLong("created_at"),
                ApprovalStatus.valueOf(rs.getString("status")),
                decision,
                rs.getLong("version"));
    }
}

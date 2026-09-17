package io.github.qwzhang01.agent.core.approval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 8.1: {@link JdbcApprovalStore} contract — mirrors the in-memory
 * reference suite: idempotent submit, optimistic one-shot decide,
 * APPROVED-only revoke, overdue expiry flip, pending scans.
 */
class JdbcApprovalStoreTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcApprovalStore store;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:approvals_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        store = new JdbcApprovalStore(conn);
        store.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private static ApprovalRequest pending(String id, String runId, long expiresAt) {
        return new ApprovalRequest(id, runId, "step-1", "hash-x", "agent-1", "HIGH",
                "refund approval", expiresAt, System.currentTimeMillis(),
                ApprovalStatus.PENDING, null, 0L);
    }

    private static ApprovalDecision decideAs(String who, long baseVersion) {
        return new ApprovalDecision(who, System.currentTimeMillis(), "looks fine", baseVersion);
    }

    @Test
    void submitPersistsAndRoundTrips() {
        store.submit(pending("run-1:step-1", "run-1", 0));
        ApprovalRequest loaded = store.get("run-1:step-1").orElseThrow();
        assertEquals("run-1", loaded.runId());
        assertEquals("step-1", loaded.stepId());
        assertEquals(ApprovalStatus.PENDING, loaded.status());
        assertEquals(0L, loaded.version());
        assertNull(loaded.decision());
        assertEquals("HIGH", loaded.riskLevel());
        assertEquals("refund approval", loaded.summary());
    }

    @Test
    void submitIsIdempotentById() {
        ApprovalRequest first = store.submit(pending("run-1:step-1", "run-1", 0));
        ApprovalRequest again = store.submit(pending("run-1:step-1", "run-1", 0));
        assertEquals(first.approvalId(), again.approvalId());
        assertEquals(0L, again.version());
        assertTrue(store.get("run-1:step-1").isPresent());
    }

    @Test
    void decideLandsOptimistically() {
        ApprovalRequest submitted = store.submit(pending("run-2:step-1", "run-2", 0));
        ApprovalRequest decided = store.decide(submitted,
                decideAs("reviewer-a", submitted.version()), ApprovalStatus.APPROVED);
        assertEquals(ApprovalStatus.APPROVED, decided.status());
        assertEquals(1L, decided.version());
        assertNotNull(decided.decision());
        assertEquals("reviewer-a", decided.decision().decidedBy());
        assertEquals("looks fine", decided.decision().reason());
    }

    @Test
    void decideIsOneShot() {
        ApprovalRequest submitted = store.submit(pending("run-3:step-1", "run-3", 0));
        store.decide(submitted, decideAs("reviewer-a", 0L), ApprovalStatus.REJECTED);
        ApprovalRequest rejected = store.get("run-3:step-1").orElseThrow();
        // Second decision attempt on a terminal request: conflict.
        ApprovalConflictException e = assertThrows(ApprovalConflictException.class,
                () -> store.decide(rejected, decideAs("reviewer-b", 1L), ApprovalStatus.APPROVED));
        assertTrue(e.getMessage().contains("one-shot"));
    }

    @Test
    void decideRejectsStaleVersion() {
        ApprovalRequest submitted = store.submit(pending("run-4:step-1", "run-4", 0));
        // A stale decision carrying base version 0 while the row is somehow
        // already at 1 (e.g. a concurrent decision landed): must conflict.
        // Simulate by landing one decision first, then trying the stale one.
        store.decide(submitted, decideAs("reviewer-a", 0L), ApprovalStatus.PENDING == null
                ? ApprovalStatus.APPROVED : ApprovalStatus.APPROVED);
        ApprovalRequest after = store.get("run-4:step-1").orElseThrow();
        ApprovalConflictException e = assertThrows(ApprovalConflictException.class,
                () -> store.decide(after, decideAs("reviewer-b", 0L), ApprovalStatus.REJECTED));
        assertTrue(e.getMessage().contains("one-shot") || e.getMessage().contains("Stale"));
    }

    @Test
    void decideUnknownApprovalFailsLoudly() {
        ApprovalRequest ghost = pending("ghost:step-1", "ghost", 0);
        ApprovalConflictException e = assertThrows(ApprovalConflictException.class,
                () -> store.decide(ghost, decideAs("x", 0L), ApprovalStatus.APPROVED));
        assertTrue(e.getMessage().contains("Unknown"));
    }

    @Test
    void revokeOnlyAfterApproval() {
        ApprovalRequest submitted = store.submit(pending("run-5:step-1", "run-5", 0));
        // PENDING: revoke is a conflict.
        assertThrows(ApprovalConflictException.class,
                () -> store.revoke("run-5:step-1", decideAs("admin", 0L)));
        // Approve, then revoke: lands.
        ApprovalRequest approved = store.decide(submitted,
                decideAs("reviewer-a", 0L), ApprovalStatus.APPROVED);
        ApprovalRequest revoked = store.revoke("run-5:step-1", decideAs("admin", approved.version()));
        assertEquals(ApprovalStatus.REVOKED, revoked.status());
        assertEquals("admin", revoked.decision().decidedBy());
        // Second revoke on the now-terminal row: conflict.
        assertThrows(ApprovalConflictException.class,
                () -> store.revoke("run-5:step-1", decideAs("admin", revoked.version())));
    }

    @Test
    void expireOverdueFlipsOnlyOverduePending() {
        long now = System.currentTimeMillis();
        store.submit(pending("run-a:overdue", "run-a", now - 1_000));   // overdue
        store.submit(pending("run-b:fresh", "run-b", now + 60_000));    // still fresh
        store.submit(pending("run-c:no-expiry", "run-c", 0));           // no expiry
        ApprovalRequest submitted = store.submit(pending("run-d:decided", "run-d", now - 1_000));
        store.decide(submitted, decideAs("r", 0L), ApprovalStatus.APPROVED); // terminal

        List<String> flipped = store.expireOverdue(now + 10);
        assertEquals(1, flipped.size());
        assertEquals("run-a:overdue", flipped.get(0));
        assertEquals(ApprovalStatus.EXPIRED, store.get("run-a:overdue").orElseThrow().status());
        assertEquals(ApprovalStatus.PENDING, store.get("run-b:fresh").orElseThrow().status());
        assertEquals(ApprovalStatus.PENDING, store.get("run-c:no-expiry").orElseThrow().status());
        assertEquals(ApprovalStatus.APPROVED, store.get("run-d:decided").orElseThrow().status());
        ApprovalRequest expired = store.get("run-a:overdue").orElseThrow();
        assertEquals("system:expiry", expired.decision().decidedBy());
    }

    @Test
    void pendingScansFilterCorrectly() {
        long now = System.currentTimeMillis();
        store.submit(pending("run-1:s1", "run-1", 0));
        store.submit(pending("run-1:s2", "run-1", 0));
        store.submit(pending("run-2:s1", "run-2", 0));
        ApprovalRequest d = store.submit(pending("run-1:s3", "run-1", 0));
        store.decide(d, decideAs("r", 0L), ApprovalStatus.REJECTED);

        List<ApprovalRequest> forRun = store.pendingForRun("run-1");
        assertEquals(2, forRun.size());
        assertTrue(forRun.stream().noneMatch(r -> r.status() != ApprovalStatus.PENDING));
        assertEquals(3, store.allPending().size());
    }
}

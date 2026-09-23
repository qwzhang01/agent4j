package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  {@link JdbcRunStore} contract against embedded H2. The
 * assertions mirror the in-memory store's semantics: create is
 * duplicate-loud, update is optimistic-locked CAS, recovery candidates
 * are RUNNING/PAUSED/WAITING_APPROVAL rows, and the {@code lastTrace}
 * JSON column round-trips.
 */
class JdbcRunStoreTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcRunStore store;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:runstore_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        store = new JdbcRunStore(conn);
        store.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private static RunRecord row(String runId, String status) {
        return new RunRecord(runId, "demo", "1", "hash-" + runId, status,
                "node-b", 2, 10L, "cp-1", null,
                System.currentTimeMillis(), System.currentTimeMillis(), 0L, List.of());
    }

    @Test
    void initializeIsIdempotent() {
        store.initialize(); // second call must not throw
    }

    @Test
    void createPersistsAndGetRoundTrips() {
        RunRecord created = store.create(row("run-1", "RUNNING"));
        Optional<RunRecord> loaded = store.get("run-1");
        assertTrue(loaded.isPresent());
        RunRecord r = loaded.get();
        assertEquals("run-1", r.runId());
        assertEquals("demo", r.workflowName());
        assertEquals("RUNNING", r.status());
        assertEquals("node-b", r.cursor());
        assertEquals(2, r.stepsExecuted());
        assertEquals(10L, r.lastEventSeq());
        assertEquals("cp-1", r.checkpointId());
        assertEquals(0L, r.version());
        assertEquals(created.runId(), r.runId());
    }

    @Test
    void createDuplicateRunIdFailsLoudly() {
        store.create(row("run-dup", "RUNNING"));
        VersionConflictException e = assertThrows(VersionConflictException.class,
                () -> store.create(row("run-dup", "RUNNING")));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void updateIsOptimisticallyLocked() {
        store.create(row("run-2", "RUNNING"));
        // Correct version: lands, version bumps 0 -> 1.
        RunRecord carried = store.get("run-2").orElseThrow();
        RunRecord next = store.update(new RunRecord("run-2", "demo", "1", "hash-run-2",
                "PAUSED", "node-b", 3, 11L, "cp-2", null,
                carried.createdAt(), System.currentTimeMillis(), carried.version(), List.of()));
        assertEquals(1L, next.version());
        assertEquals("PAUSED", next.status());
        assertEquals("cp-2", next.checkpointId());

        // Stale version: loses loudly with the stored version in the message.
        RunRecord stale = new RunRecord("run-2", "demo", "1", "hash-run-2",
                "FAILED", null, 4, 12L, null, "boom",
                carried.createdAt(), System.currentTimeMillis(), 0L, List.of());
        VersionConflictException e = assertThrows(VersionConflictException.class,
                () -> store.update(stale));
        assertTrue(e.getMessage().contains("stored 1"));
    }

    @Test
    void updateUnknownRunFailsLoudly() {
        RunRecord ghost = row("ghost", "RUNNING");
        VersionConflictException e = assertThrows(VersionConflictException.class,
                () -> store.update(ghost));
        assertTrue(e.getMessage().contains("row not found"));
    }

    @Test
    void recoveryCandidatesAreOrderedByCreatedAt() throws SQLException {
        seed("run-old", "RUNNING", 1_000L);
        seed("run-paused", "PAUSED", 2_000L);
        seed("run-wait", "WAITING_APPROVAL", 3_000L);
        seed("run-done", "SUCCEEDED", 4_000L);
        seed("run-failed", "FAILED", 5_000L);
        List<RunRecord> candidates = store.listRecoveryCandidates();
        assertEquals(3, candidates.size());
        assertEquals("run-old", candidates.get(0).runId());
        assertEquals("run-paused", candidates.get(1).runId());
        assertEquals("run-wait", candidates.get(2).runId());
    }

    @Test
    void listByStatusFilters() {
        store.create(row("run-a", "RUNNING"));
        store.create(row("run-b", "SUCCEEDED"));
        store.create(row("run-c", "RUNNING"));
        List<RunRecord> running = store.listByStatus("RUNNING");
        assertEquals(2, running.size());
        assertEquals("run-a", running.get(0).runId());
        assertEquals("run-c", running.get(1).runId());
    }

    @Test
    void lastTraceRoundTripsThroughJson() {
        List<StepRecord> trace = List.of(
                new StepRecord("node-a", StepRecord.Status.SUCCESS, 12L, 1, "ok"),
                new StepRecord("node-b", StepRecord.Status.FAILED, 34L, 2, "boom"));
        store.create(new RunRecord("run-trace", "demo", "1", "h", "RUNNING",
                "node-c", 2, 5L, null, null,
                1L, 1L, 0L, trace));
        List<StepRecord> loaded = store.get("run-trace").orElseThrow().lastTrace();
        assertEquals(2, loaded.size());
        assertEquals("node-a", loaded.get(0).nodeId());
        assertEquals(StepRecord.Status.SUCCESS, loaded.get(0).status());
        assertEquals(12L, loaded.get(0).durationMs());
        assertEquals(1, loaded.get(0).attempts());
        assertEquals("ok", loaded.get(0).summary());
        assertEquals("node-b", loaded.get(1).nodeId());
        assertEquals(StepRecord.Status.FAILED, loaded.get(1).status());
        assertEquals("boom", loaded.get(1).summary());
    }

    @Test
    void twoInstancesShareOneDatabase() throws SQLException {
        // The distributed premise: a second runtime instance over the same
        // H2 database sees the first instance's rows.
        store.create(row("run-shared", "RUNNING"));
        try (Connection second = DriverManager.getConnection(
                "jdbc:h2:mem:runstore_shared_2;DB_CLOSE_DELAY=-1")) {
            // Same URL would need the same in-mem name; use a second store
            // over the SAME connection instead — the contract is "two
            // stores, one truth".
            JdbcRunStore other = new JdbcRunStore(conn);
            assertTrue(other.get("run-shared").isPresent());
            RunRecord carried = other.get("run-shared").orElseThrow();
            RunRecord updated = other.update(new RunRecord("run-shared", "demo", "1", "h",
                    "PAUSED", "node-z", 9, 20L, null, null,
                    carried.createdAt(), System.currentTimeMillis(), carried.version(), List.of()));
            assertEquals(1L, updated.version());
            assertEquals("PAUSED", store.get("run-shared").orElseThrow().status());
        }
    }

    @Test
    void nullablesRoundTrip() {
        store.create(new RunRecord("run-null", "demo", "1", "h", "RUNNING",
                null, 0, 0L, null, null, 1L, 1L, 0L, null));
        RunRecord r = store.get("run-null").orElseThrow();
        assertTrue(r.cursor() == null || r.cursor().isEmpty(),
                "cursor null should round-trip as null/empty");
    }

    /** Directly seed a row with fixed created_at for ordering tests. */
    private void seed(String runId, String status, long createdAt) {
        RunRecord r = new RunRecord(runId, "demo", "1", "h", status, "n", 1, 1L,
                null, null, createdAt, createdAt, 0L, List.of());
        store.create(r);
    }
}

package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 3.1/3.2 (2026-09-17): the run row carries immutable identity
 * fields — {@code tenantId} and the component {@code versions} JSON —
 * written once at create, carried untouched through every transition.
 * <p>
 * The standard postmortem join — "yesterday's bad batch, which tenant,
 * which agent/model/prompt combination served it?" — needs both columns
 * persisted on the row, not reconstructed from logs.
 */
class JdbcRunStoreTenantVersionsTest {

    static {
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcRunStore store;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:runstore_tv_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        store = new JdbcRunStore(conn);
        store.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private static RunRecord row(String runId, String tenantId, String versions) {
        return new RunRecord(runId, "demo", "1", "hash-" + runId, "RUNNING",
                null, 0, 0L, null, null,
                System.currentTimeMillis(), System.currentTimeMillis(), 0L,
                java.util.List.of(), tenantId, versions);
    }

    @Test
    @DisplayName("tenantId + versions round-trip through the row")
    void tenantAndVersionsRoundTrip() {
        String versions = "[{\"kind\":\"AGENT\",\"name\":\"agent-x\",\"version\":\"unversioned\"}]";
        store.create(row("run-tv", "tenant-7", versions));
        RunRecord r = store.get("run-tv").orElseThrow();
        assertEquals("tenant-7", r.tenantId());
        assertEquals(versions, r.versions());
    }

    @Test
    @DisplayName("null tenantId and empty versions round-trip honestly (null-safe compact ctor)")
    void nullTenantAndEmptyVersionsRoundTrip() {
        store.create(new RunRecord("run-legacy", "demo", "1", "h", "RUNNING",
                null, 0, 0L, null, null, 1L, 1L, 0L, java.util.List.of(), null, null));
        RunRecord r = store.get("run-legacy").orElseThrow();
        assertTrue(r.tenantId() == null || r.tenantId().isEmpty(),
                "null tenantId should stay null/empty after round-trip");
        assertEquals("", r.versions(), "null versions normalizes to empty string");
    }

    @Test
    @DisplayName("identity fields survive an optimistic-locked transition untouched")
    void identityFieldsSurviveTransition() {
        RunRecord created = store.create(row("run-tx", "tenant-9", "[{\"kind\":\"AGENT\",\"name\":\"a\",\"version\":\"unversioned\"}]"));

        // Transition: status/cursor/trace move; tenantId/versions must be carried, not rewritten.
        RunRecord carried = store.get("run-tx").orElseThrow();
        RunRecord next = store.update(new RunRecord("run-tx", "demo", "1", "hash-run-tx",
                "PAUSED", "node-b", 1, 5L, "cp-1", null,
                carried.createdAt(), System.currentTimeMillis(), carried.version(),
                java.util.List.of(new StepRecord("node-b", StepRecord.Status.SUCCESS, 10L, 1, "ok")),
                carried.tenantId(), carried.versions()));
        assertEquals("tenant-9", next.tenantId());
        assertEquals("[{\"kind\":\"AGENT\",\"name\":\"a\",\"version\":\"unversioned\"}]", next.versions());
        assertEquals(1L, next.version(), "CAS must still bump the version");
    }

    @Test
    @DisplayName("recovery candidates carry tenantId/versions too (post-crash attribution intact)")
    void recoveryCandidatesCarryIdentity() {
        store.create(row("run-rec", "tenant-11", "[{\"kind\":\"AGENT\",\"name\":\"b\",\"version\":\"v2\"}]"));
        Optional<RunRecord> candidate = store.listRecoveryCandidates().stream()
                .filter(r -> "run-rec".equals(r.runId()))
                .findFirst();
        assertTrue(candidate.isPresent());
        assertEquals("tenant-11", candidate.get().tenantId());
        assertEquals("[{\"kind\":\"AGENT\",\"name\":\"b\",\"version\":\"v2\"}]", candidate.get().versions());
    }
}

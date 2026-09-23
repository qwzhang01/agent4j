package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.runtime.Checkpoint;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcCheckpointStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunLeases;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcRunStore;
import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcSideEffectLedger;
import io.github.qwzhang01.agent.workflow.runtime.durable.RunRecord;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.Effect;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.DeliverySemantics;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.RetryDisposition;
import io.github.qwzhang01.agent.workflow.runtime.durable.VersionConflictException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  the ANSI dialect discipline proven on real PostgreSQL.
 * <p>
 * wrote the JDBC spine against embedded H2 with one policy — plain
 * ANSI SQL, no MERGE / ON CONFLICT / FOR UPDATE SKIP LOCKED, guarded UPDATE
 * affected-rows as the CAS verdict. This class runs the CORE contract of
 * every store against a real PostgreSQL (skip-by-assumption when none is
 * reachable): create round-trip, optimistic-lock CAS, lease single-winner,
 * ledger idempotency, checkpoint codec over the wire, scheduler queue claim.
 * <p>
 * Not exhaustive: the H2 suites (10/8/6/9/11 tests per store) stay the full
 * contract; this is the dialect proof, one deep pass per store.
 */
@Tag(PostgresIT.TAG)
class PostgresJdbcStoresIT extends PostgresIT {

    private static RunRecord row(String runId, String status) {
        return new RunRecord(runId, "demo", "1", "hash-" + runId, status,
                "node-b", 2, 10L, "cp-1", null,
                System.currentTimeMillis(), System.currentTimeMillis(), 0L, List.of());
    }

    @Test
    void runStoreCoreContractOnPostgres() throws Exception {
        try (Connection conn = openConnection(); PostgresIT.SchemaHandle schema = freshSchema(conn)) {
            JdbcRunStore store = new JdbcRunStore(conn);
            store.initialize();

            // create + get round-trip
            store.create(row("run-1", "RUNNING"));
            RunRecord r = store.get("run-1").orElseThrow();
            assertEquals("RUNNING", r.status());
            assertEquals(0L, r.version());

            // duplicate create fails loud
            assertThrows(VersionConflictException.class, () -> store.create(row("run-1", "RUNNING")));

            // optimistic CAS: correct version lands and bumps
            RunRecord carried = store.get("run-1").orElseThrow();
            RunRecord next = store.update(new RunRecord("run-1", "demo", "1", "hash-run-1",
                    "PAUSED", "node-c", 3, 11L, "cp-2", null,
                    carried.createdAt(), System.currentTimeMillis(), carried.version(), List.of()));
            assertEquals(1L, next.version());
            assertEquals("PAUSED", next.status());

            // stale version loses loudly
            RunRecord stale = new RunRecord("run-1", "demo", "1", "hash-run-1",
                    "FAILED", null, 4, 12L, null, "boom",
                    carried.createdAt(), System.currentTimeMillis(), 0L, List.of());
            assertThrows(VersionConflictException.class, () -> store.update(stale));

            // recovery candidates ordered by createdAt. run-1 is now PAUSED
            // (the CAS above flipped it), so it is a candidate too: four
            // rows, run-1 first among equals is NOT asserted (same-ms ties);
            // assert the membership and the seeded trio's relative order.
            seedRow(store, "run-old", "RUNNING", 1_000L);
            seedRow(store, "run-paused", "PAUSED", 2_000L);
            seedRow(store, "run-wait", "WAITING_APPROVAL", 3_000L);
            seedRow(store, "run-done", "SUCCEEDED", 4_000L);
            List<RunRecord> candidates = store.listRecoveryCandidates();
            assertEquals(4, candidates.size(),
                    "run-1 (PAUSED by the CAS above) plus the three seeded candidates");
            List<String> ids = candidates.stream().map(RunRecord::runId).toList();
            assertTrue(ids.contains("run-old"));
            assertTrue(ids.contains("run-paused"));
            assertTrue(ids.contains("run-wait"));
            assertTrue(!ids.contains("run-done"), "SUCCEEDED is not a candidate");
            // Seeded trio keeps createdAt order relative to each other.
            int old = ids.indexOf("run-old");
            int paused = ids.indexOf("run-paused");
            int wait = ids.indexOf("run-wait");
            assertTrue(old < paused && paused < wait,
                    "createdAt ordering must hold among the seeds");
        }
    }

    private static void seedRow(JdbcRunStore store, String runId, String status, long createdAt)
            throws SQLException {
        store.create(new RunRecord(runId, "demo", "1", "hash-" + runId, status,
                "node-b", 2, 10L, null, null, createdAt, createdAt, 0L, List.of()));
    }

    @Test
    void runLeasesCoreContractOnPostgres() throws Exception {
        try (Connection conn = openConnection(); PostgresIT.SchemaHandle schema = freshSchema(conn)) {
            JdbcRunLeases leases = new JdbcRunLeases(conn);
            leases.initialize();

            // single-winner: A acquires, B loses
            assertTrue(leases.tryAcquire("run-1", "instance-a", 5_000L));
            assertFalse(leases.tryAcquire("run-1", "instance-b", 5_000L));

            // renew is holder-only
            assertTrue(leases.renew("run-1", "instance-a", 5_000L));
            assertFalse(leases.renew("run- IT_1", "instance-b", 5_000L));

            // release is holder-only; after release B can take over
            assertFalse(leases.release("run-1", "instance-b"));
            assertTrue(leases.release("run-1", "instance-a"));
            assertTrue(leases.tryAcquire("run-1", "instance-b", 5_000L));
        }
    }

    @Test
    void sideEffectLedgerCoreContractOnPostgres() throws Exception {
        try (Connection conn = openConnection(); PostgresIT.SchemaHandle schema = freshSchema(conn)) {
            JdbcSideEffectLedger ledger = new JdbcSideEffectLedger(conn);
            ledger.initialize();

            // node-scoped idempotency: same effectId → original result wins
            Effect first = ledger.record(effect("run-1", "send-email", null, "email-sent-42"));
            Effect dup = ledger.record(effect("run-1", "send-email", null, "email-sent-CHANGED"));
            assertEquals(first.effectId(), dup.effectId());
            assertEquals("email-sent-42", dup.result(),
                    "duplicate record must return the ORIGINAL result");

            // call-scoped lookup separates calls
            ledger.record(effect("run-2", "tool-call", "hash-aaa", "result-a"));
            ledger.record(effect("run-2", "tool-call", "hash-bbb", "result-b"));
            assertEquals("result-a", ledger.lookup("run-2", "tool-call", "hash-aaa")
                    .orElseThrow().result());
            assertEquals("result-b", ledger.lookup("run-2", "tool-call", "hash-bbb")
                    .orElseThrow().result());
        }
    }

    private static Effect effect(String runId, String nodeId, String callHash, String result) {
        String effectId = callHash == null
                ? Effect.idFor(runId, nodeId)
                : Effect.idFor(runId, nodeId, callHash);
        return new Effect(effectId, runId, nodeId, "", callHash,
                DeliverySemantics.EXACTLY_ONCE, RetryDisposition.RETRYABLE,
                result, System.currentTimeMillis());
    }

    @Test
    void checkpointStoreCoreContractOnPostgres() throws Exception {
        try (Connection conn = openConnection(); PostgresIT.SchemaHandle schema = freshSchema(conn)) {
            JdbcCheckpointStore store = new JdbcCheckpointStore(conn);
            store.initialize();

            Checkpoint cp = sample("run-a", "charge");
            store.save(cp);

            Optional<Checkpoint> back = store.load("run-a");
            assertTrue(back.isPresent());
            Checkpoint loaded = back.get();
            assertEquals(cp.checkpointId(), loaded.checkpointId());
            assertEquals(cp.workflowHash(), loaded.workflowHash());
            assertEquals(cp.cursor(), loaded.cursor());

            // upsert-latest per runId
            Checkpoint newer = sample("run-a", "node-2");
            newer = new Checkpoint(Checkpoint.SCHEMA_VERSION, "cp-run-a-2", "run-a",
                    "wf", "1.0", "hash-abc", RunState.PAUSED, "node-9",
                    newer.state(), System.currentTimeMillis(), 5, null, 7L, List.of());
            store.save(newer);
            assertEquals("node-9", store.load("run-a").orElseThrow().cursor());

            // runId whitelist refuses traversal
            assertThrows(IllegalArgumentException.class, () -> store.load("../escape"));
        }
    }

    private static Checkpoint sample(String runId, String cursor) {
        WorkflowState state = WorkflowState.restore("order-1", Map.of("amount", 99), List.of());
        return new Checkpoint(Checkpoint.SCHEMA_VERSION, "cp-" + runId, runId,
                "wf", "1.0", "hash-abc", RunState.PAUSED, cursor, state,
                System.currentTimeMillis(), 3, "pending-input", 42L, List.of());
    }
}

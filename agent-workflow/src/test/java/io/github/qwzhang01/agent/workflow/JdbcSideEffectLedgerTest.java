package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.durable.JdbcSideEffectLedger;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.DeliverySemantics;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.Effect;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger.RetryDisposition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *  {@link JdbcSideEffectLedger} contract — idempotent record
 * (PK collision returns the original), node-scoped and call-scoped
 * lookup, per-run and global listing.
 */
class JdbcSideEffectLedgerTest {

    static {
        // DriverManager's ServiceLoader discovery is racy under in-process
        // (forkCount=0) runs where module classloaders share one JVM; forcing
        // H2 class init self-registers the driver regardless of SPI timing.
        org.h2.Driver.load();
    }

    private Connection conn;
    private JdbcSideEffectLedger ledger;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:effects_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1");
        ledger = new JdbcSideEffectLedger(conn);
        ledger.initialize();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
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
    void recordThenLookupNodeScoped() {
        ledger.record(effect("run-1", "send-email", null, "email-sent-42"));
        Optional<Effect> hit = ledger.lookup("run-1", "send-email");
        assertTrue(hit.isPresent());
        assertEquals("email-sent-42", hit.get().result());
        assertEquals(DeliverySemantics.EXACTLY_ONCE, hit.get().semantics());
        assertEquals(RetryDisposition.RETRYABLE, hit.get().disposition());
        assertEquals(Effect.idFor("run-1", "send-email"), hit.get().effectId());
    }

    @Test
    void recordIsIdempotentByEffectId() {
        Effect first = ledger.record(effect("run-1", "charge-card", null, "txn-1"));
        // A crash-replay records the same effect again: original wins.
        Effect dup = ledger.record(effect("run-1", "charge-card", null, "txn-1-CHANGED"));
        assertEquals(first.effectId(), dup.effectId());
        assertEquals("txn-1", dup.result(),
                "duplicate record must return the ORIGINAL result, not overwrite");
    }

    @Test
    void callScopedLookupSeparatesCalls() {
        ledger.record(effect("run-2", "tool-call", "hash-aaa", "result-a"));
        ledger.record(effect("run-2", "tool-call", "hash-bbb", "result-b"));
        assertEquals("result-a", ledger.lookup("run-2", "tool-call", "hash-aaa")
                .orElseThrow().result());
        assertEquals("result-b", ledger.lookup("run-2", "tool-call", "hash-bbb")
                .orElseThrow().result());
        // Different run: no hit.
        assertTrue(ledger.lookup("run-other", "tool-call", "hash-aaa").isEmpty());
    }

    @Test
    void lookupMissReturnsEmpty() {
        assertTrue(ledger.lookup("run-ghost", "nope").isEmpty());
        assertTrue(ledger.lookup("run-ghost", "nope", "hash").isEmpty());
    }

    @Test
    void effectsForRunFiltersAndOrders() throws InterruptedException {
        long t0 = System.currentTimeMillis();
        ledger.record(new Effect(Effect.idFor("run-3", "n1"), "run-3", "n1", "",
                null, DeliverySemantics.AT_MOST_ONCE, RetryDisposition.NOT_RETRYABLE,
                "r1", t0));
        Thread.sleep(5);
        ledger.record(new Effect(Effect.idFor("run-3", "n2"), "run-3", "n2", "",
                null, DeliverySemantics.AT_LEAST_ONCE, RetryDisposition.RETRYABLE,
                "r2", t0 + 5));
        ledger.record(new Effect(Effect.idFor("run-other", "n3"), "run-other", "n3", "",
                null, DeliverySemantics.EXACTLY_ONCE, RetryDisposition.RETRYABLE,
                "r3", t0 + 10));

        List<Effect> forRun = ledger.effectsForRun("run-3");
        assertEquals(2, forRun.size());
        assertEquals("n1", forRun.get(0).nodeId());
        assertEquals("n2", forRun.get(1).nodeId());
        assertEquals(3, ledger.allEffects().size());
    }

    @Test
    void idempotencyKeyAndArgsHashRoundTrip() {
        ledger.record(new Effect(Effect.idFor("run-4", "n", "abc123"), "run-4", "n",
                "biz-key-7", "abc123", DeliverySemantics.EXACTLY_ONCE,
                RetryDisposition.NEEDS_CONFIRMATION, "outcome", 1L));
        // call-scoped effect: the call-scoped lookup finds it
        Effect loaded = ledger.lookup("run-4", "n", "abc123").orElseThrow();
        assertEquals("biz-key-7", loaded.idempotencyKey());
        assertEquals("abc123", loaded.argsHash());
        assertEquals(RetryDisposition.NEEDS_CONFIRMATION, loaded.disposition());
        assertEquals(1L, loaded.completedAt());
        // ... and the node-scoped lookup (no call hash) does not claim it
        assertTrue(ledger.lookup("run-4", "n").isEmpty(),
                "a call-scoped effect must not match the node-scoped lookup");
    }
}

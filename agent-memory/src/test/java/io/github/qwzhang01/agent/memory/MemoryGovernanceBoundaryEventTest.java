package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Harness 4.4 (2026-09-17): the memory boundary emits telemetry twins for
 * every governed access — success or refusal — alongside the audit ledger.
 * The events carry structure (operation/purpose/count/masked/duration),
 * never memory content.
 */
class MemoryGovernanceBoundaryEventTest {

    private InMemoryMemoryStore store;
    private List<BoundaryEvent> events;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        events = new ArrayList<>();
    }

    private RunContext ctxOf(String tenantId, String userId) {
        return RunContext.builder().tenantId(tenantId).userId(userId).build();
    }

    @Test
    @DisplayName("query success emits MemoryAccessed with count/masked/duration")
    void querySuccessEmitsAccessed() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null, events::add);
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "pref", "dark mode", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        gov.query(MemoryQuery.builder().scopes(List.of("user:u1")).build(),
                ctxOf("acme", "u1"), "context-recall");

        assertEquals(1, events.size());
        BoundaryEvent.MemoryAccessed e = (BoundaryEvent.MemoryAccessed) events.get(0);
        assertEquals("query", e.operation());
        assertEquals("context-recall", e.purpose());
        assertEquals(1, e.resultCount());
        assertEquals(false, e.masked(), "no maskable pattern in 'dark mode'");
        assertTrue(e.durationMs() >= 0);
        assertEquals(1, e.schemaVersion());
    }

    @Test
    @DisplayName("query refusal emits MemoryAccessFailed before the store is touched")
    void queryRefusalEmitsFailed() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null, events::add);

        // Blank purpose: refused before the store is touched.
        assertThrows(IllegalArgumentException.class, () -> gov.query(
                MemoryQuery.builder().scopes(List.of("user:u1")).build(),
                ctxOf("acme", "u1"), " "));

        assertEquals(1, events.size());
        BoundaryEvent.MemoryAccessFailed e = (BoundaryEvent.MemoryAccessFailed) events.get(0);
        assertEquals("query", e.operation());
        assertTrue(e.reason().contains("purpose"), "the refusal reason is the store's own exception text");
    }

    @Test
    @DisplayName("no-identity query refuses with a fact, not silence")
    void queryNoIdentityRefusesWithFact() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null, events::add);

        assertThrows(IllegalArgumentException.class, () -> gov.query(
                MemoryQuery.builder().scopes(List.of("user:u1")).build(),
                RunContext.create(), "context-recall"));

        assertEquals(1, events.size());
        assertTrue(events.get(0) instanceof BoundaryEvent.MemoryAccessFailed);
    }

    @Test
    @DisplayName("write success and cross-scope refusal both emit their twins")
    void writeSuccessAndRefusalEmitTwins() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null, events::add);

        MemoryEntry ok = new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "harmless", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);
        gov.write(ok, ctxOf("acme", "u1"), "save-memory");

        MemoryEntry crossTenant = new MemoryEntry(null, "user:u2", MemoryType.FACT, "x", "poison", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);
        assertThrows(IllegalArgumentException.class,
                () -> gov.write(crossTenant, ctxOf("acme", "u1"), "save-memory"));

        assertEquals(2, events.size());
        BoundaryEvent.MemoryAccessed success = (BoundaryEvent.MemoryAccessed) events.get(0);
        assertEquals("write", success.operation());
        assertEquals(1, success.resultCount());
        assertTrue(events.get(1) instanceof BoundaryEvent.MemoryAccessFailed,
                "cross-scope write refusal emits the failed twin");
    }

    @Test
    @DisplayName("throwing sink never breaks the governed access (side channel)")
    void throwingSinkNeverBreaksAccess() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null,
                e -> { throw new IllegalStateException("sink broken"); });

        MemoryEntry ok = new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "harmless", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);
        gov.write(ok, ctxOf("acme", "u1"), "save-memory");

        assertEquals(1, store.listByScope("user:u1").size(),
                "the write landed even though the telemetry sink threw");
    }

    @Test
    @DisplayName("legacy 3-arg constructor emits nothing: byte-for-byte legacy behavior")
    void legacyConstructorEmitsNothing() {
        MemoryGovernance gov = new MemoryGovernance(store, null, null);

        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "k", "v", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));
        gov.query(MemoryQuery.builder().scopes(List.of("user:u1")).build(),
                ctxOf("acme", "u1"), "context-recall");

        // No assertion on events: the legacy wiring has no sink, nothing to
        // observe — only that the call itself succeeds without incident.
        assertEquals(1, store.listByScope("user:u1").size());
    }
}

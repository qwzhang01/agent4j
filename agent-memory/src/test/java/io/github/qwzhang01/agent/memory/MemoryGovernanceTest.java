package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.redact.SecretMasker;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5.2: governed access over the memory store.
 * <p>
 * The raw {@code MemoryStore} trusts callers; {@link MemoryGovernance} adds
 * identity-bound scopes, per-access audit, mandatory purpose, content
 * redaction, and verifiable deletion propagation.
 */
class MemoryGovernanceTest {

    private InMemoryMemoryStore store;
    private MemoryGovernance gov;
    private List<MemoryAccessAuditRecord> auditTrail;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        auditTrail = new java.util.ArrayList<>();
        gov = new MemoryGovernance(store, null, auditTrail::add);
    }

    private RunContext ctxOf(String tenantId, String userId) {
        return RunContext.builder().tenantId(tenantId).userId(userId).build();
    }

    // Scope derivation (identity-bound)

    @Test
    void scopesFor_derivesFromIdentity() {
        RunContext ctx = RunContext.builder()
                .tenantId("acme").userId("u1").channelId("c1").agentId("bot").build();
        List<String> scopes = MemoryGovernance.scopesFor(ctx);
        assertEquals(List.of("tenant:acme", "user:u1", "channel:c1", "agent:bot"), scopes);
    }

    @Test
    void scopesFor_emptyIdentityYieldsEmptyList() {
        assertTrue(MemoryGovernance.scopesFor(RunContext.create()).isEmpty());
    }

    @Test
    void query_scopesFromContext_notFromQuery() {
        MemoryEntry secret = store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT,
                "api-key", "my key is sk-abcdefghijklmnopqrst", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u2", MemoryType.FACT,
                "api-key", "u2 private secret", 0.9,
                MemoryProvenance.userSaid("u2", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        // The query object tries to ask for BOTH users' scopes
        MemoryQuery query = MemoryQuery.builder()
                .scopes(List.of("user:u1", "user:u2")).build();

        MemoryGovernance.MemoryReadResult result = gov.query(query, ctxOf("acme", "u1"), "context-recall");

        assertEquals(1, result.entries().size(), "u2's scope is outside the identity whitelist");
        assertTrue(result.masked(), "content containing a secret pattern is masked for the consumer");
        assertFalse(result.entries().get(0).content().contains("sk-abcdefghijklmnopqrst"),
                "raw secret must not reach the consumer");
        assertTrue(result.entries().get(0).content().contains("[REDACTED:api-key]"));
    }

    @Test
    void query_noIdentity_throwsFailLoud() {
        MemoryQuery query = MemoryQuery.builder().scopes(List.of("user:u1")).build();
        assertThrows(IllegalArgumentException.class,
                () -> gov.query(query, RunContext.create(), "context-recall"));
    }

    @Test
    void query_emitsAuditRecord() {
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "pref", "dark mode", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        MemoryQuery query = MemoryQuery.builder().scopes(List.of("user:u1")).build();
        gov.query(query, ctxOf("acme", "u1"), "context-recall");

        assertEquals(1, auditTrail.size());
        MemoryAccessAuditRecord r = auditTrail.get(0);
        assertEquals(MemoryAccessAuditRecord.Operation.READ, r.operation());
        assertEquals("acme", r.tenantId());
        assertEquals("u1", r.userId());
        assertEquals("context-recall", r.purpose());
        assertEquals(1, r.resultCount());
        assertFalse(r.masked(), "no maskable pattern in 'dark mode'");
    }

    @Test
    void query_blankPurposeRejected() {
        MemoryQuery query = MemoryQuery.builder().scopes(List.of("user:u1")).build();
        assertThrows(IllegalArgumentException.class, () -> gov.query(query, ctxOf("acme", "u1"), " "));
        assertThrows(IllegalArgumentException.class, () -> gov.query(query, ctxOf("acme", "u1"), null));
    }

    @Test
    void query_rawOnlyPolicy_passthrough() {
        MemoryGovernance rawGov = new MemoryGovernance(store, io.github.qwzhang01.agent.core.redact.RedactionPolicy.rawOnly(), auditTrail::add);
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "k", "key sk-abcdefghijklmnopqrst here", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        MemoryQuery query = MemoryQuery.builder().scopes(List.of("user:u1")).build();
        MemoryGovernance.MemoryReadResult result = rawGov.query(query, ctxOf("acme", "u1"), "context-recall");

        assertTrue(result.entries().get(0).content().contains("sk-abcdefghijklmnopqrst"),
                "rawOnly policy: legacy passthrough behaviour, byte-for-byte");
    }

    @Test
    void write_scopeMustBeInsideWhitelist() {
        MemoryEntry crossTenant = new MemoryEntry(null, "user:u2", MemoryType.FACT, "x", "poison", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);

        assertThrows(IllegalArgumentException.class,
                () -> gov.write(crossTenant, ctxOf("acme", "u1"), "save-memory"));
        assertTrue(store.listByScope("user:u2").isEmpty(), "rejected write never lands");
    }

    @Test
    void write_withinWhitelist_audited() {
        MemoryEntry ok = new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "harmless", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);
        gov.write(ok, ctxOf("acme", "u1"), "save-memory");

        assertEquals(1, auditTrail.size());
        assertEquals(MemoryAccessAuditRecord.Operation.WRITE, auditTrail.get(0).operation());
        assertEquals("save-memory", auditTrail.get(0).purpose());
        assertEquals(1, auditTrail.get(0).resultCount());
    }

    @Test
    void write_blankPurposeRejected() {
        MemoryEntry ok = new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "harmless", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null);
        assertThrows(IllegalArgumentException.class, () -> gov.write(ok, ctxOf("acme", "u1"), ""));
    }

    @Test
    void purgeForUser_deletesAndPropagates() {
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "a", "fact a", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "b", "fact b", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u2", MemoryType.FACT, "c", "fact c", 0.9,
                MemoryProvenance.userSaid("u2", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        MemoryGovernance.DeletionPropagation p = gov.purgeForUser(ctxOf("acme", "u1"), "u1");

        assertEquals(2, p.removedEntryIds().size());
        assertTrue(store.listByScope("user:u1").isEmpty(), "u1's entries all gone");
        assertEquals(1, store.listByScope("user:u2").size(), "u2 untouched");
        assertEquals(List.of("user:u1"), p.scopes());
        assertEquals("u1", p.userId());
    }

    @Test
    void purgeForTenant_sweepsTenantScope() {
        store.write(new MemoryEntry(null, "tenant:acme", MemoryType.FACT, "kb", "knowledge base entry", 0.9,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));
        store.write(new MemoryEntry(null, "tenant:globex", MemoryType.FACT, "kb", "other tenant", 0.9,
                MemoryProvenance.userSaid("u2", "r1", Instant.now()), MemoryStatus.ACTIVE,
                Instant.now(), null));

        MemoryGovernance.DeletionPropagation p = gov.purgeForTenant(ctxOf("acme", "admin"), "acme");

        assertEquals(1, p.removedEntryIds().size());
        assertTrue(store.listByScope("tenant:acme").isEmpty());
        assertEquals(1, store.listByScope("tenant:globex").size(), "other tenant untouched");
    }
}

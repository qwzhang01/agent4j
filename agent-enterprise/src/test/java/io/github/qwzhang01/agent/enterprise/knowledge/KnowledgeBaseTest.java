package io.github.qwzhang01.agent.enterprise.knowledge;

import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.2: the tenant knowledge base (D5 "knowledge is memory").
 */
class KnowledgeBaseTest {

    private InMemoryMemoryStore store;
    private KnowledgeBase knowledge;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        knowledge = new KnowledgeBase(store);
    }

    private KnowledgeEntry entry(String title, String content) {
        return new KnowledgeEntry(title, content, "doc.pdf", Set.of("faq"));
    }

    // ============ Ingest -> Search Round-Trip ============

    @Test
    @DisplayName("ingest then keyword search returns the matching entry")
    void ingestSearchRoundTrip() {
        knowledge.ingest("acme", List.of(
                entry("Return Policy", "Acme offers 30-day no-question returns on all items"),
                entry("Invoice Policy", "Invoices are issued within 24 hours of shipment"),
                entry("Membership", "Gold members get free shipping on every order")
        ), "admin");

        List<KnowledgeEntry> hits = knowledge.search("acme", "return", 3);

        assertEquals(1, hits.size());
        assertEquals("Return Policy", hits.get(0).title());
        assertTrue(hits.get(0).content().contains("30-day"));
    }

    @Test
    @DisplayName("blank query returns the newest entries within topK")
    void blankQueryReturnsAll() {
        knowledge.ingest("acme", List.of(
                entry("A", "alpha"), entry("B", "beta"), entry("C", "gamma")
        ), "admin");

        List<KnowledgeEntry> hits = knowledge.search("acme", " ", 2);
        assertEquals(2, hits.size(), "topK limits the unfiltered recall");
    }

    @Test
    @DisplayName("ingest with null/empty list is a no-op")
    void emptyIngestNoOp() {
        knowledge.ingest("acme", null, "admin");
        knowledge.ingest("acme", List.of(), "admin");
        assertEquals(0, knowledge.count("acme"));
    }

    @Test
    @DisplayName("count only sees KNOWLEDGE entries of that tenant")
    void countIsTypeAndScopeBounded() {
        knowledge.ingest("acme", List.of(entry("A", "alpha"), entry("B", "beta")), "admin");
        knowledge.ingest("globex", List.of(entry("G", "gamma")), "admin");

        assertEquals(2, knowledge.count("acme"));
        assertEquals(1, knowledge.count("globex"));
    }

    // ============ Cross-Tenant Zero Leakage ============

    @Test
    @DisplayName("searching acme never returns globex entries (and vice versa)")
    void crossTenantZeroLeakage() {
        knowledge.ingest("acme", List.of(
                entry("Return Policy", "acme policy: 30-day returns allowed")), "admin");
        knowledge.ingest("globex", List.of(
                entry("Return Policy", "globex policy: all sales final")), "admin");

        List<KnowledgeEntry> fromAcme = knowledge.search("acme", "policy", 10);
        List<KnowledgeEntry> fromGlobex = knowledge.search("globex", "policy", 10);

        assertEquals(1, fromAcme.size());
        assertTrue(fromAcme.get(0).content().contains("acme policy"));
        assertEquals(1, fromGlobex.size());
        assertTrue(fromGlobex.get(0).content().contains("globex policy"));
    }

    @Test
    @DisplayName("one shared store safely serves multiple tenants (isolation by scope, not by instance)")
    void sharedStoreMultiTenant() {
        // the standard deployment shape: a single store, many tenants
        knowledge.ingest("acme", List.of(entry("A", "acme secret alpha")), "admin");
        knowledge.ingest("globex", List.of(entry("B", "globex secret beta")), "admin");
        knowledge.ingest("initech", List.of(entry("C", "initech secret gamma")), "admin");

        assertEquals("acme secret alpha", knowledge.search("acme", "secret", 10).get(0).content());
        assertEquals("globex secret beta", knowledge.search("globex", "secret", 10).get(0).content());
        assertEquals("initech secret gamma", knowledge.search("initech", "secret", 10).get(0).content());
        assertEquals(1, knowledge.search("acme", "secret", 10).size());
    }

    // ============ KNOWLEDGE Type Purity ============

    @Test
    @DisplayName("search never returns non-KNOWLEDGE entries even in the same tenant scope")
    void knowledgeTypePurity() {
        // a FACT written directly into the tenant scope (e.g. legacy data or
        // a misrouted write) must not surface as knowledge
        store.write(new MemoryEntry(
                null, "tenant:acme", MemoryType.FACT, "legacy-note",
                "a legacy fact that mentions returns", 0.5,
                MemoryProvenance.userSaid("someone", "r1", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null));
        knowledge.ingest("acme", List.of(
                entry("Return Policy", "the real return policy mentions returns")), "admin");

        List<KnowledgeEntry> hits = knowledge.search("acme", "returns", 10);

        assertEquals(1, hits.size());
        assertEquals("Return Policy", hits.get(0).title());
    }

    // ============ topK ============

    @Test
    @DisplayName("topK truncates results; non-positive topK falls back to default")
    void topKTruncation() {
        knowledge.ingest("acme", List.of(
                entry("P1", "policy one"), entry("P2", "policy two"),
                entry("P3", "policy three"), entry("P4", "policy four"),
                entry("P5", "policy five")), "admin");

        assertEquals(2, knowledge.search("acme", "policy", 2).size());
        assertEquals(KnowledgeEntry.DEFAULT_TOP_K,
                knowledge.search("acme", "policy", 0).size());
    }

    // ============ Entry Validation ============

    @Test
    @DisplayName("KnowledgeEntry rejects blank title/content; null tags tolerated")
    void entryValidation() {
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEntry.of("  ", "content"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeEntry.of("title", ""));
        assertEquals(Set.of(), new KnowledgeEntry("t", "c", null, null).tags());
    }

    @Test
    @DisplayName("source/tags are import-side metadata: not persisted, not fabricated on retrieval")
    void honestMetadataBoundary() {
        KnowledgeEntry rich = new KnowledgeEntry("T", "C", "doc.pdf", Set.of("x"));
        MemoryEntry stored = rich.toMemoryEntry("acme", "admin");
        assertEquals(MemoryType.KNOWLEDGE, stored.type());
        assertEquals("tenant:acme", stored.scope());
        assertEquals("T", stored.subject());

        KnowledgeEntry back = KnowledgeEntry.fromMemoryEntry(stored);
        assertEquals("T", back.title());
        assertEquals("C", back.content());
        assertEquals(null, back.source());
        assertEquals(Set.of(), back.tags());
    }

    // ============ Argument Validation ============

    @Test
    @DisplayName("blank tenantId fails fast")
    void blankTenantRejected() {
        assertThrows(IllegalArgumentException.class, () -> knowledge.search(" ", "q", 3));
        assertThrows(NullPointerException.class, () -> knowledge.ingest(null, List.of(), "a"));
    }
}

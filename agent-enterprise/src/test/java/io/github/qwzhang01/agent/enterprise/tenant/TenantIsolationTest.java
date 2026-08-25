package io.github.qwzhang01.agent.enterprise.tenant;

import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryRetriever;
import io.github.qwzhang01.agent.memory.MemoryScope;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.1 core test: tenant isolation via the scope whitelist.
 * <p>
 * The blueprint's claim under test (D3): "isolation is mechanism, not
 * convention". Cross-tenant leakage is the most severe enterprise security
 * accident - this test proves it cannot happen at the store level, using the
 * same {@code tenant:*} scopes that {@link RequestContext#memoryScopes()}
 * emits. Knowledge retrieval (M15.2) will sit on exactly this guarantee.
 */
class TenantIsolationTest {

    private InMemoryMemoryStore store;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        retriever = new MemoryRetriever(store);
    }

    private MemoryEntry knowledge(String scope, String subject, String content) {
        return new MemoryEntry(
                null, scope, MemoryType.KNOWLEDGE, subject, content, 0.9,
                MemoryProvenance.adminEdit("admin", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
    }

    private MemoryEntry fact(String scope, String subject, String content) {
        return new MemoryEntry(
                null, scope, MemoryType.FACT, subject, content, 0.5,
                MemoryProvenance.userSaid("tester", "run-1", Instant.now()),
                MemoryStatus.ACTIVE, Instant.now(), null
        );
    }

    // ============ Scope Format ============

    @Test
    @DisplayName("MemoryScope.tenant() produces well-formed tenant scopes")
    void tenantScopeFormat() {
        MemoryScope scope = MemoryScope.tenant("acme");
        assertEquals("tenant:acme", scope.value());
        assertEquals(MemoryScope.Kind.TENANT, scope.kind());
        assertEquals("acme", scope.id());
    }

    @Test
    @DisplayName("tenant scopes round-trip through of()")
    void tenantScopeRoundTrip() {
        MemoryScope parsed = MemoryScope.of("tenant:globex");
        assertEquals(MemoryScope.Kind.TENANT, parsed.kind());
        assertEquals("globex", parsed.id());
    }

    // ============ Cross-Tenant Zero Leakage ============

    @Test
    @DisplayName("globex cannot retrieve acme's tenant-scoped entries (and vice versa)")
    void crossTenantZeroLeakage() {
        // both contents mention the same searchable keyword ("policy") so the
        // only thing that can differ between the two retrievals is the scope
        store.write(knowledge("tenant:acme", "return-policy", "Acme policy: 30-day no-question returns"));
        store.write(knowledge("tenant:globex", "return-policy", "Globex policy: all sales final"));

        List<MemoryEntry> fromAcme = retriever.recallByKeyword(
                List.of("tenant:acme"), "policy");
        List<MemoryEntry> fromGlobex = retriever.recallByKeyword(
                List.of("tenant:globex"), "policy");

        assertEquals(1, fromAcme.size(), "acme sees exactly its own policy");
        assertEquals("Acme policy: 30-day no-question returns", fromAcme.get(0).content());
        assertEquals(1, fromGlobex.size(), "globex sees exactly its own policy");
        assertEquals("Globex policy: all sales final", fromGlobex.get(0).content());
    }

    @Test
    @DisplayName("empty whitelist retrieves nothing (fail-closed, never full-store)")
    void emptyWhitelistIsEmpty() {
        store.write(knowledge("tenant:acme", "faq", "secret sauce"));

        List<MemoryEntry> result = store.query(
                MemoryQuery.builder().scopes(List.of()).build());

        assertTrue(result.isEmpty(), "empty scope list must not degrade into full-store scan");
    }

    // ============ User Privacy Boundary ============

    @Test
    @DisplayName("one user's memories are invisible to another user's whitelist")
    void userPrivacyBoundary() {
        store.write(fact("user:u-alice", "diet", "allergic to peanuts"));
        store.write(fact("user:u-bob", "diet", "loves peanuts"));

        List<MemoryEntry> aliceView = retriever.recall(List.of("user:u-alice"));
        List<MemoryEntry> bobView = retriever.recall(List.of("user:u-bob"));

        assertEquals(1, aliceView.size());
        assertTrue(aliceView.get(0).content().contains("allergic"));
        assertEquals(1, bobView.size());
        assertTrue(bobView.get(0).content().contains("loves"));
    }

    // ============ RequestContext Whitelist Semantics ============

    @Test
    @DisplayName("the whitelist from login retrieves exactly tenant + own user scopes")
    void contextWhitelistSeesOwnDataOnly() {
        TenantRegistry registry = new TenantRegistry();
        registry.registerTenant(Tenant.active("acme", "Acme Corp"));
        registry.registerTenant(Tenant.active("globex", "Globex Inc"));
        registry.registerUser(new User("u-alice", "acme", "Alice", Set.of(User.ROLE_CSR)), "k1");
        registry.registerUser(new User("u-bob", "acme", "Bob", Set.of(User.ROLE_CSR)), "k2");
        registry.registerUser(new User("u-carol", "globex", "Carol", Set.of(User.ROLE_CSR)), "k3");

        // data belonging to three different parties
        store.write(knowledge("tenant:acme", "faq", "acme shared knowledge"));
        store.write(knowledge("tenant:globex", "faq", "globex secret knowledge"));
        store.write(fact("user:u-alice", "pref", "alice prefers email"));
        store.write(fact("user:u-bob", "pref", "bob prefers phone"));

        RequestContext ctx = registry.login("acme", "u-alice", "k1");

        // exactly what the whitelist promises: acme knowledge + alice's own memory
        List<MemoryEntry> visible = retriever.recall(ctx.memoryScopes());
        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(e -> e.content().contains("acme shared knowledge")));
        assertTrue(visible.stream().anyMatch(e -> e.content().contains("alice prefers email")));
        // bob's memory and globex's knowledge are both invisible
        assertTrue(visible.stream().noneMatch(e -> e.content().contains("bob prefers phone")));
        assertTrue(visible.stream().noneMatch(e -> e.content().contains("globex secret knowledge")));
    }

    // ============ KNOWLEDGE Type Filter ============

    @Test
    @DisplayName("KNOWLEDGE type filters tenant entries without mixing in FACT/PREFERENCE")
    void knowledgeTypeFilter() {
        store.write(knowledge("tenant:acme", "faq", "how to file a return"));
        store.write(fact("tenant:acme", "note", "acme tenant-level fact"));

        List<MemoryEntry> knowledgeOnly = retriever.recall(
                List.of("tenant:acme"), MemoryType.KNOWLEDGE);

        assertEquals(1, knowledgeOnly.size());
        assertEquals("how to file a return", knowledgeOnly.get(0).content());
        assertEquals(MemoryType.KNOWLEDGE, knowledgeOnly.get(0).type());
    }
}

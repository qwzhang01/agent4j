package io.github.qwzhang01.agent.memory.store;

import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Machine-checkable port contract for {@link MemoryStore} (memory roadmap
 * step 4). The javadoc promises — scope isolation, ACTIVE-only default view
 * with status opt-in, lazy TTL filtering, exact filters, due windows,
 * newest-first ordering, store-assigned ids, upsert-on-id writes, the
 * supersede ledger move, and full field round-trips — run identically against
 * every implementation: {@link InMemoryMemoryStore} on every build
 * ({@link InMemoryMemoryStoreContractTest}) and {@link PgMemoryStore} against
 * a real PostgreSQL when {@code AGENT4J_PG_TEST=true}
 * ({@link PgMemoryStoreContractTest}). A new store implementation proves the
 * contract by extending this class — the port behaviour no longer lives only
 * in javadoc.
 */
abstract class MemoryStoreContractTest {

    protected static final Instant T0 = Instant.parse("2026-09-01T00:00:00Z");
    protected static final Instant T1 = Instant.parse("2026-09-02T00:00:00Z");
    protected static final Instant T2 = Instant.parse("2026-09-03T00:00:00Z");

    /** Fresh, empty store per test. */
    protected abstract MemoryStore newStore();

    protected MemoryStore store;

    @BeforeEach
    void setUp() {
        store = newStore();
    }

    protected MemoryEntry entry(String scope, String subject, String content,
                                MemoryStatus status, Instant createdAt) {
        return new MemoryEntry(null, scope, MemoryType.FACT, subject, content, 0.8,
                MemoryProvenance.userSaid("tester", "run-1", createdAt), status, createdAt, null);
    }

    private List<MemoryEntry> queryActive(String... scopes) {
        return store.query(MemoryQuery.builder().scopes(List.of(scopes)).build());
    }

    // ============ Scope Isolation ============

    @Test
    void scopeIsolation_foreignScopeNeverVisible() {
        store.write(entry("user:u1", "diet", "allergic to peanuts", MemoryStatus.ACTIVE, T0));

        assertTrue(queryActive("user:u2").isEmpty(), "user:u2 must not see user:u1's memories");
    }

    @Test
    void sharedChannelScope_visibleToBothUsers() {
        store.write(entry("channel:c1", "diet", "team lunch on Friday", MemoryStatus.ACTIVE, T0));

        List<MemoryEntry> fromA = queryActive("user:u1", "channel:c1");
        List<MemoryEntry> fromB = queryActive("user:u2", "channel:c1");

        assertEquals(1, fromA.size());
        assertEquals(1, fromB.size());
        assertEquals("team lunch on Friday", fromA.get(0).content());
    }

    // ============ Default ACTIVE-only view / status opt-in ============

    @Test
    void defaultQuery_returnsActiveOnly() {
        store.write(entry("user:u1", "s-active", "live fact", MemoryStatus.ACTIVE, T0));
        store.write(entry("user:u1", "s-pending", "awaiting review", MemoryStatus.PENDING_REVIEW, T0));
        store.write(entry("user:u1", "s-rejected", "rejected", MemoryStatus.REJECTED, T0));
        store.write(entry("user:u1", "s-superseded", "wrong from the start", MemoryStatus.SUPERSEDED, T0));
        store.write(entry("user:u1", "s-historical", "once true", MemoryStatus.HISTORICAL, T0));

        List<MemoryEntry> result = queryActive("user:u1");
        assertEquals(1, result.size(), "default view is ACTIVE only");
        assertEquals("live fact", result.get(0).content());
    }

    @Test
    void statusOptIn_historicalSurfaces() {
        store.write(entry("user:u1", "s-active", "live fact", MemoryStatus.ACTIVE, T1));
        store.write(entry("user:u1", "s-historical", "once true", MemoryStatus.HISTORICAL, T0));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1"))
                .statuses(MemoryStatus.ACTIVE, MemoryStatus.HISTORICAL)
                .build());

        assertEquals(2, result.size(), "explicit status list opts into history");
    }

    // ============ TTL (lazy, on retrieval) ============

    @Test
    void ttl_entriesPastDeadlineLazilyFiltered() {
        MemoryEntry alive = new MemoryEntry(null, "user:u1", MemoryType.FACT, "ttl-live", "still valid",
                0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0,
                Instant.now().plusSeconds(3600));
        MemoryEntry dead = new MemoryEntry(null, "user:u1", MemoryType.FACT, "ttl-dead", "expired",
                0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0,
                Instant.now().minusSeconds(3600));
        store.write(alive);
        store.write(dead);

        List<MemoryEntry> result = queryActive("user:u1");
        assertEquals(1, result.size());
        assertEquals("still valid", result.get(0).content());
    }

    // ============ Exact filters ============

    @Test
    void typeFilter_onlyMatchingTypeReturned() {
        MemoryEntry pref = new MemoryEntry(null, "user:u1", MemoryType.PREFERENCE, "s-pref", "dark mode",
                0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0, null);
        store.write(pref);
        store.write(entry("user:u1", "s-fact", "standup at 10", MemoryStatus.ACTIVE, T0));

        List<MemoryEntry> prefs = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).type(MemoryType.PREFERENCE).build());
        assertEquals(1, prefs.size());
        assertEquals("dark mode", prefs.get(0).content());
    }

    @Test
    void subjectFilter_exactMatchOnly() {
        store.write(entry("user:u1", "home-city", "Shenzhen", MemoryStatus.ACTIVE, T0));
        store.write(entry("user:u1", "home", "shorter subject", MemoryStatus.ACTIVE, T0));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).subject("home-city").build());
        assertEquals(1, result.size(), "subject filter is exact, not prefix");
        assertEquals("Shenzhen", result.get(0).content());
    }

    @Test
    void keywordFilter_caseInsensitiveContains() {
        store.write(entry("user:u1", "diet", "Allergic to Peanuts", MemoryStatus.ACTIVE, T0));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).keyword("peanut").build());
        assertEquals(1, result.size(), "keyword match is case-insensitive contains");
    }

    @Test
    void dueWindow_inclusiveBoundsNullDueExcluded() {
        MemoryEntry due = new MemoryEntry(null, "user:u1", MemoryType.EVENT, "s-due", "has due",
                0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0, null, T1);
        store.write(due);
        store.write(entry("user:u1", "s-nodue", "no due time", MemoryStatus.ACTIVE, T0));

        List<MemoryEntry> inWindow = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).dueBetween(T1, T1).build());
        assertEquals(1, inWindow.size(), "inclusive bounds; a null dueAt never matches a window");
        assertEquals("has due", inWindow.get(0).content());
    }

    // ============ Ordering / limit ============

    @Test
    void newestFirst_thenLimitTruncates() {
        store.write(entry("user:u1", "s0", "oldest", MemoryStatus.ACTIVE, T0));
        store.write(entry("user:u1", "s1", "middle", MemoryStatus.ACTIVE, T1));
        store.write(entry("user:u1", "s2", "newest", MemoryStatus.ACTIVE, T2));

        List<MemoryEntry> all = queryActive("user:u1");
        assertEquals(List.of("newest", "middle", "oldest"),
                all.stream().map(MemoryEntry::content).toList());

        List<MemoryEntry> limited = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).limit(2).build());
        assertEquals(List.of("newest", "middle"),
                limited.stream().map(MemoryEntry::content).toList());
    }

    // ============ findActiveBySubject ============

    @Test
    void findActiveBySubject_ignoresHistoricalAndOtherScopes() {
        store.write(entry("user:u1", "home-city", "Beijing", MemoryStatus.HISTORICAL, T0));
        store.write(entry("user:u1", "home-city", "Shenzhen", MemoryStatus.ACTIVE, T1));
        store.write(entry("user:u2", "home-city", "other user", MemoryStatus.ACTIVE, T2));

        MemoryEntry found = store.findActiveBySubject("user:u1", "home-city").orElseThrow();
        assertEquals("Shenzhen", found.content());
    }

    @Test
    void findActiveBySubject_emptyWhenOnlyHistorical() {
        store.write(entry("user:u1", "home-city", "Beijing", MemoryStatus.HISTORICAL, T0));

        assertTrue(store.findActiveBySubject("user:u1", "home-city").isEmpty());
    }

    // ============ Update / upsert-write / delete ============

    @Test
    void updateNonexistentEntry_throws() {
        MemoryEntry ghost = new MemoryEntry("no-such-id", "user:u1", MemoryType.FACT, "ghost",
                "no row", 0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0, null);

        assertThrows(IllegalArgumentException.class, () -> store.update(ghost));
    }

    @Test
    void writeWithExistingId_upsertsInPlace() {
        MemoryEntry first = store.write(entry("user:u1", "home-city", "Shenzhen", MemoryStatus.ACTIVE, T0));
        MemoryEntry rewritten = new MemoryEntry(first.id(), "user:u1", MemoryType.FACT, "home-city",
                "Shanghai", 0.9, MemoryProvenance.userSaid("t", "r2", T1), MemoryStatus.ACTIVE, T1, null);
        store.write(rewritten);

        assertEquals(1, store.listByScope("user:u1").size(), "re-write must not duplicate the row");
        assertEquals("Shanghai", store.findById(first.id()).orElseThrow().content());
    }

    @Test
    void delete_trueThenFalse() {
        MemoryEntry e = store.write(entry("user:u1", "s", "c", MemoryStatus.ACTIVE, T0));

        assertTrue(store.delete(e.id()));
        assertFalse(store.delete(e.id()));
        assertTrue(store.findById(e.id()).isEmpty());
    }

    @Test
    void listByScope_anyStatusNewestFirst() {
        store.write(entry("user:u1", "s-hist", "old line", MemoryStatus.HISTORICAL, T0));
        store.write(entry("user:u1", "s-active", "new line", MemoryStatus.ACTIVE, T1));
        store.write(entry("user:u2", "s-other", "other scope", MemoryStatus.ACTIVE, T2));

        List<MemoryEntry> all = store.listByScope("user:u1");
        assertEquals(2, all.size(), "any status, scope-bounded");
        assertEquals("new line", all.get(0).content());
    }

    // ============ Supersede ledger move ============

    @Test
    void supersede_closesOldWritesNew() {
        MemoryEntry old = store.write(entry("user:u1", "home-city", "Shenzhen", MemoryStatus.ACTIVE, T0));
        MemoryEntry replacement = entry("user:u1", "home-city", "Shanghai", MemoryStatus.ACTIVE, T1);

        MemoryEntry written = store.supersede(
                old.closedAs(MemoryStatus.HISTORICAL, T1, T2), replacement);

        assertNotNull(written.id(), "store assigns the id when null");

        MemoryEntry closedRow = store.findById(old.id()).orElseThrow();
        assertEquals(MemoryStatus.HISTORICAL, closedRow.status());
        assertEquals(T1, closedRow.validAt(), "business axis ends at the new fact's business start");
        assertEquals(T2, closedRow.invalidAt(), "system axis ends at the ledger close time");

        List<MemoryEntry> active = queryActive("user:u1");
        assertEquals(1, active.size());
        assertEquals("Shanghai", active.get(0).content());
        assertEquals(written.id(), active.get(0).id());
    }

    // ============ Round-trips ============

    @Test
    void embeddingRoundTrip() {
        float[] vector = {0.1f, -0.2f, 0.3f};
        MemoryEntry withVector = new MemoryEntry(null, "user:u1", MemoryType.FACT, "s-vec", "vectors",
                0.8, MemoryProvenance.userSaid("t", "r", T0), MemoryStatus.ACTIVE, T0,
                null, null, null, vector);
        MemoryEntry written = store.write(withVector);

        float[] read = store.findById(written.id()).orElseThrow().embedding();
        assertNotNull(read);
        assertArrayEquals(vector, read);
    }

    @Test
    void provenanceAndBiTemporalRoundTrip() {
        MemoryEntry rich = new MemoryEntry(null, "user:u1", MemoryType.FACT, "s-rich", "rich entry",
                0.75, MemoryProvenance.modelDerived("model-x", "run-42", T0), MemoryStatus.ACTIVE, T0,
                null, null, MemoryLifecycle.EVOLVE, null, T0, T1, T2);
        MemoryEntry written = store.write(rich);
        MemoryEntry read = store.findById(written.id()).orElseThrow();

        assertEquals(MemoryProvenance.modelDerived("model-x", "run-42", T0), read.provenance());
        assertEquals(MemoryLifecycle.EVOLVE, read.lifecycle());
        assertEquals(T0, read.validFrom());
        assertEquals(T1, read.validAt());
        assertEquals(T2, read.invalidAt());
    }
}

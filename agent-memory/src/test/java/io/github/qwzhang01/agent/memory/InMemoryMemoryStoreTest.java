package io.github.qwzhang01.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8 M8.1 tests: data model, scope isolation, store CRUD, TTL, status filtering.
 */
class InMemoryMemoryStoreTest {

    private InMemoryMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
    }

    private MemoryEntry entry(String scope, String subject, String content, MemoryStatus status) {
        return new MemoryEntry(
                null, scope, MemoryType.FACT, subject, content, 0.8,
                MemoryProvenance.userSaid("tester", "run-1", Instant.now()),
                status, Instant.now(), null
        );
    }

    // ============ Scope Isolation ============

    @Test
    void userScopeIsolation_otherUserCannotSee() {
        store.write(entry("user:u1", "diet", "allergic to peanuts", MemoryStatus.ACTIVE));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u2"))
                .build());

        assertTrue(result.isEmpty(), "user:u2 must not see user:u1's memories");
    }

    @Test
    void channelScope_multipleUsersCanSee() {
        store.write(entry("channel:c1", "diet", "team lunch on Friday", MemoryStatus.ACTIVE));

        List<MemoryEntry> fromA = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1", "channel:c1"))
                .build());
        List<MemoryEntry> fromB = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u2", "channel:c1"))
                .build());

        assertEquals(1, fromA.size(), "user A sees channel memory");
        assertEquals(1, fromB.size(), "user B sees the same channel memory");
        assertEquals("team lunch on Friday", fromA.get(0).content());
    }

    @Test
    void mixedScopes_aggregatesFromAllListed() {
        store.write(entry("user:u1", "pref", "dark mode", MemoryStatus.ACTIVE));
        store.write(entry("channel:c1", "event", "standup at 10am", MemoryStatus.ACTIVE));
        store.write(entry("agent:bot", "fact", "timezone UTC+8", MemoryStatus.ACTIVE));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1", "channel:c1", "agent:bot"))
                .build());

        assertEquals(3, result.size(), "should aggregate from all listed scopes");
    }

    // ============ Status Filtering ============

    @Test
    void query_onlyReturnsActive() {
        store.write(entry("user:u1", "s1", "active one", MemoryStatus.ACTIVE));
        store.write(entry("user:u1", "s2", "pending one", MemoryStatus.PENDING_REVIEW));
        store.write(entry("user:u1", "s3", "rejected one", MemoryStatus.REJECTED));
        store.write(entry("user:u1", "s4", "superseded one", MemoryStatus.SUPERSEDED));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1"))
                .build());

        assertEquals(1, result.size());
        assertEquals("active one", result.get(0).content());
    }

    @Test
    void pendingReview_notRetrivableUntilApproved() {
        MemoryEntry pending = store.write(entry("channel:c1", "diet", "maybe allergic", MemoryStatus.PENDING_REVIEW));

        assertTrue(store.query(MemoryQuery.builder().scopes(List.of("channel:c1")).build()).isEmpty());

        store.update(pending.withStatus(MemoryStatus.ACTIVE));
        List<MemoryEntry> result = store.query(MemoryQuery.builder().scopes(List.of("channel:c1")).build());
        assertEquals(1, result.size());
    }

    // ============ TTL ============

    @Test
    void expiredEntry_lazilyFiltered() {
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        MemoryEntry expired = new MemoryEntry(
                null, "user:u1", MemoryType.FACT, "temp", "old news", 0.5,
                MemoryProvenance.userSaid("u1", "r1", past),
                MemoryStatus.ACTIVE, past, Instant.now().minus(1, ChronoUnit.MINUTES)
        );
        store.write(expired);
        store.write(entry("user:u1", "fresh", "new news", MemoryStatus.ACTIVE));

        List<MemoryEntry> result = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1"))
                .build());

        assertEquals(1, result.size());
        assertEquals("new news", result.get(0).content());
    }

    // ============ Filters: type / subject / keyword / limit ============

    @Test
    void filters_typeSubjectKeywordLimit() {
        store.write(new MemoryEntry(null, "user:u1", MemoryType.PREFERENCE, "ui", "dark mode", 0.7,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "tz", "timezone UTC+8", 0.6,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));
        store.write(new MemoryEntry(null, "user:u1", MemoryType.PREFERENCE, "lang", "prefers English", 0.6,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));

        // type filter
        assertEquals(2, store.query(MemoryQuery.builder().scopes(List.of("user:u1")).type(MemoryType.PREFERENCE).build()).size());

        // keyword filter
        assertEquals(1, store.query(MemoryQuery.builder().scopes(List.of("user:u1")).keyword("dark").build()).size());

        // subject filter
        assertEquals(1, store.query(MemoryQuery.builder().scopes(List.of("user:u1")).subject("tz").build()).size());

        // limit
        assertEquals(1, store.query(MemoryQuery.builder().scopes(List.of("user:u1")).limit(1).build()).size());
    }

    // ============ Conflict / Supersede ============

    @Test
    void findActiveBySubject_returnsLatestActive() {
        store.write(entry("user:u1", "diet", "first claim", MemoryStatus.SUPERSEDED));
        MemoryEntry latest = store.write(entry("user:u1", "diet", "corrected claim", MemoryStatus.ACTIVE));

        var found = store.findActiveBySubject("user:u1", "diet");
        assertTrue(found.isPresent());
        assertEquals("corrected claim", found.get().content());
        assertEquals(latest.id(), found.get().id());
    }

    @Test
    void supersede_oldEntryBecomesInvisible() {
        MemoryEntry old = store.write(entry("user:u1", "diet", "wrong", MemoryStatus.ACTIVE));
        store.update(old.withStatus(MemoryStatus.SUPERSEDED));
        store.write(entry("user:u1", "diet", "right", MemoryStatus.ACTIVE));

        var found = store.findActiveBySubject("user:u1", "diet");
        assertTrue(found.isPresent());
        assertEquals("right", found.get().content());
    }

    // ============ CRUD ============

    @Test
    void update_nonExistentThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                store.update(new MemoryEntry("ghost", "user:u1", MemoryType.FACT, "x", "y", 0.5,
                        MemoryProvenance.userSaid("u1", "r1", Instant.now()),
                        MemoryStatus.ACTIVE, Instant.now(), null)));
    }

    @Test
    void delete_removesEntry() {
        MemoryEntry e = store.write(entry("user:u1", "x", "to delete", MemoryStatus.ACTIVE));
        assertTrue(store.delete(e.id()));
        assertFalse(store.delete(e.id()));
        assertTrue(store.findById(e.id()).isEmpty());
    }

    @Test
    void listByScope_returnsAllStatuses() {
        store.write(entry("user:u1", "a", "active", MemoryStatus.ACTIVE));
        store.write(entry("user:u1", "b", "pending", MemoryStatus.PENDING_REVIEW));
        store.write(entry("channel:c1", "c", "other scope", MemoryStatus.ACTIVE));

        assertEquals(2, store.listByScope("user:u1").size());
        assertEquals(1, store.listByScope("channel:c1").size());
    }

    // ============ Provenance ============

    @Test
    void provenance_preservedThroughWrite() {
        MemoryProvenance prov = MemoryProvenance.modelDerived("gpt-4", "run-99", Instant.parse("2026-08-19T10:00:00Z"));
        MemoryEntry e = store.write(new MemoryEntry(
                null, "user:u1", MemoryType.SUMMARY, "ctx", "summarized history", 0.5,
                prov, MemoryStatus.ACTIVE, Instant.now(), null));

        MemoryEntry loaded = store.findById(e.id()).orElseThrow();
        assertEquals(MemoryProvenance.SourceType.MODEL_DERIVED, loaded.provenance().sourceType());
        assertEquals("gpt-4", loaded.provenance().actor());
        assertEquals("run-99", loaded.provenance().runId());
    }

    // ============ MemoryScope ============

    @Test
    void scope_parsing() {
        assertEquals(MemoryScope.Kind.USER, MemoryScope.user("u1").kind());
        assertEquals("u1", MemoryScope.user("u1").id());
        assertEquals(MemoryScope.Kind.CHANNEL, MemoryScope.channel("c1").kind());
        assertEquals(MemoryScope.Kind.AGENT, MemoryScope.of("agent:bot").kind());
        assertThrows(IllegalArgumentException.class, () -> MemoryScope.of("invalid"));
    }
}

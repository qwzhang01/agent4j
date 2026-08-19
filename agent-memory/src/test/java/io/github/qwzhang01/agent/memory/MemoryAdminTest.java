package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 8 M8.4 tests: channel-scope pending review, MemoryAdmin governance,
 * supersede correction, TTL.
 */
class MemoryAdminTest {

    private InMemoryMemoryStore store;
    private MemoryAdmin admin;
    private MemoryExtractor extractor;
    private MemoryPolicy policy;
    private MemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        admin = new MemoryAdmin(store);
        extractor = new MemoryExtractor();
        policy = new MemoryPolicy(0.5);
        retriever = new MemoryRetriever(store);
    }

    // ============ Channel scope defaults to PENDING_REVIEW ============

    @Test
    void channelWrite_defaultsToPendingReview() {
        List<ChatMessage> msgs = List.of(ChatMessage.user("记住我对花生过敏"));
        extractor.extractAndStore(msgs, "channel:c1",
                MemoryProvenance.userSaid("userA", "r1", Instant.now()), policy, store);

        List<MemoryEntry> all = store.listByScope("channel:c1");
        assertEquals(1, all.size());
        assertEquals(MemoryStatus.PENDING_REVIEW, all.get(0).status(),
                "channel writes default to PENDING_REVIEW");
    }

    @Test
    void channelPending_notRetrievable() {
        extractor.extractAndStore(List.of(ChatMessage.user("记住我对花生过敏")), "channel:c1",
                MemoryProvenance.userSaid("userA", "r1", Instant.now()), policy, store);

        // User B queries channel:c1 -> sees nothing (pending, not active)
        assertTrue(retriever.recall(List.of("channel:c1")).isEmpty());
    }

    // ============ Approve flow (A stores, admin approves, B sees) ============

    @Test
    void approveFlow_userAStores_adminApproves_userBSees() {
        // User A states a fact in channel c1
        extractor.extractAndStore(List.of(ChatMessage.user("记住我对花生过敏")), "channel:c1",
                MemoryProvenance.userSaid("userA", "r1", Instant.now()), policy, store);

        // Before approval: B sees nothing
        assertTrue(retriever.recall(List.of("channel:c1")).isEmpty());

        // Admin approves
        MemoryEntry pending = store.listByScope("channel:c1").get(0);
        admin.approve(pending.id());

        // After approval: B can retrieve it
        List<MemoryEntry> visible = retriever.recall(List.of("channel:c1"));
        assertEquals(1, visible.size());
        assertEquals("记住我对花生过敏", visible.get(0).content());
        assertEquals(MemoryStatus.ACTIVE, visible.get(0).status());
    }

    @Test
    void approve_nonPendingThrows() {
        MemoryEntry active = store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "x", "y", 0.8,
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), MemoryStatus.ACTIVE, Instant.now(), null));
        assertThrows(IllegalStateException.class, () -> admin.approve(active.id()));
    }

    @Test
    void reject_makesEntryInvisible() {
        extractor.extractAndStore(List.of(ChatMessage.user("记住我对花生过敏")), "channel:c1",
                MemoryProvenance.userSaid("userA", "r1", Instant.now()), policy, store);

        MemoryEntry pending = store.listByScope("channel:c1").get(0);
        admin.reject(pending.id());

        assertEquals(MemoryStatus.REJECTED, store.findById(pending.id()).get().status());
        assertTrue(retriever.recall(List.of("channel:c1")).isEmpty());
    }

    // ============ Supersede (correction) ============

    @Test
    void supersede_correctionOldBecomesSuperseded() {
        // Admin adds a fact
        MemoryEntry original = admin.addEntry("channel:c1", MemoryType.FACT, "diet",
                "user A is allergic to peanuts", "admin1");

        // Later discovered it was wrong, admin corrects via supersede
        MemoryEntry corrected = admin.supersede(original.id(),
                "user A is NOT allergic to peanuts", "admin1");

        // Old entry is SUPERSEDED
        assertEquals(MemoryStatus.SUPERSEDED, store.findById(original.id()).get().status());
        // New entry is ACTIVE
        assertEquals(MemoryStatus.ACTIVE, corrected.status());
        assertEquals("user A is NOT allergic to peanuts", corrected.content());

        // Retrieval returns only the corrected one
        List<MemoryEntry> visible = retriever.recall(List.of("channel:c1"));
        assertEquals(1, visible.size());
        assertEquals(corrected.id(), visible.get(0).id());
    }

    @Test
    void supersede_keepsHistoryForAudit() {
        MemoryEntry original = admin.addEntry("channel:c1", MemoryType.FACT, "diet", "wrong claim", "admin1");
        admin.supersede(original.id(), "correct claim", "admin1");

        // listByScope returns ALL statuses (admin view)
        List<MemoryEntry> all = admin.listByScope("channel:c1");
        assertEquals(2, all.size(), "both old (SUPERSEDED) and new (ACTIVE) kept for audit");
    }

    // ============ Admin content edit ============

    @Test
    void updateContent_changesContentWithAdminProvenance() {
        MemoryEntry entry = admin.addEntry("user:u1", MemoryType.PREFERENCE, "ui", "dark mode", "admin1");

        MemoryEntry edited = admin.updateContent(entry.id(), "dark mode preferred", "admin2");

        assertEquals("dark mode preferred", edited.content());
        assertEquals(MemoryProvenance.SourceType.ADMIN_EDIT, edited.provenance().sourceType());
        assertEquals("admin2", edited.provenance().actor());
    }

    // ============ TTL ============

    @Test
    void setTtl_entryExpiresAndBecomesInvisible() {
        MemoryEntry entry = admin.addEntry("user:u1", MemoryType.FACT, "temp", "temporary fact", "admin1");

        // Set TTL to 1 hour ago -> already expired
        admin.setTtl(entry.id(), Instant.now().minus(1, ChronoUnit.HOURS));

        assertTrue(retriever.recall(List.of("user:u1")).isEmpty(), "expired entry not retrievable");
    }

    @Test
    void setTtl_futureTtl_stillVisible() {
        MemoryEntry entry = admin.addEntry("user:u1", MemoryType.FACT, "temp", "temporary fact", "admin1");

        admin.setTtl(entry.id(), Instant.now().plus(1, ChronoUnit.HOURS));

        assertEquals(1, retriever.recall(List.of("user:u1")).size());
    }

    // ============ listPending ============

    @Test
    void listPending_onlyReturnsPendingEntries() {
        extractor.extractAndStore(List.of(
                ChatMessage.user("记住我喜欢深色模式"),
                ChatMessage.user("记住我讨厌浅色模式")
        ), "channel:c1", MemoryProvenance.userSaid("u1", "r1", Instant.now()), policy, store);

        admin.addEntry("channel:c1", MemoryType.FACT, "manual", "admin fact", "admin1");

        List<MemoryEntry> pending = admin.listPending("channel:c1");
        assertEquals(2, pending.size(), "2 extracted entries pending, admin entry is active");
        assertTrue(pending.stream().allMatch(e -> e.status() == MemoryStatus.PENDING_REVIEW));
    }

    // ============ Delete ============

    @Test
    void delete_hardRemovesEntry() {
        MemoryEntry entry = admin.addEntry("user:u1", MemoryType.FACT, "x", "to delete", "admin1");
        assertTrue(admin.delete(entry.id()));
        assertTrue(store.findById(entry.id()).isEmpty());
        assertFalse(admin.delete(entry.id()));
    }

    @Test
    void findById_returnsEntry() {
        MemoryEntry entry = admin.addEntry("user:u1", MemoryType.FACT, "x", "find me", "admin1");
        assertTrue(admin.findById(entry.id()).isPresent());
        assertTrue(admin.findById("nonexistent").isEmpty());
    }

    // ============ Non-channel scopes default to ACTIVE ============

    @Test
    void userScope_defaultsToActive() {
        extractor.extractAndStore(List.of(ChatMessage.user("记住我喜欢深色模式")), "user:u1",
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), policy, store);

        List<MemoryEntry> all = store.listByScope("user:u1");
        assertEquals(1, all.size());
        assertEquals(MemoryStatus.ACTIVE, all.get(0).status(), "user scope defaults to ACTIVE");
    }

    @Test
    void agentScope_defaultsToActive() {
        extractor.extractAndStore(List.of(ChatMessage.user("记住我喜欢深色模式")), "agent:bot",
                MemoryProvenance.userSaid("u1", "r1", Instant.now()), policy, store);

        assertEquals(MemoryStatus.ACTIVE, store.listByScope("agent:bot").get(0).status());
    }
}

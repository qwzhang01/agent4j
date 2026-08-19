package io.github.qwzhang01.agent.examples;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.memory.*;

import java.time.Instant;
import java.util.List;

/**
 * Stage 8 acceptance example 3: channel-shared memory + governance.
 * <p>
 * Demonstrates the Claude Tag-style channel memory flow:
 * - User A states a fact in channel c1 -> stored as PENDING_REVIEW
 * - Admin reviews and approves -> becomes ACTIVE
 * - User B (different user) queries channel c1 -> sees the approved memory
 * - Admin corrects a wrong fact -> old entry SUPERSEDED, new entry ACTIVE
 * <p>
 * Run: mvn compile exec:java -pl examples -Dexec.mainClass=io.github.qwzhang01.agent.examples.ChannelMemoryExample
 */
public class ChannelMemoryExample {

    public static void main(String[] args) {
        System.out.println("=== Stage 8: Channel-Shared Memory + Governance ===\n");

        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryAdmin admin = new MemoryAdmin(store);
        MemoryRetriever retriever = new MemoryRetriever(store);
        MemoryExtractor extractor = new MemoryExtractor();
        MemoryPolicy policy = new MemoryPolicy(0.5);

        // ---- T1: User A states a fact in channel c1 ----
        System.out.println("--- T1: User A states a fact ---");
        System.out.println("User A (in channel c1): 记住我对花生过敏");

        int stored = extractor.extractAndStore(
                List.of(ChatMessage.user("记住我对花生过敏")),
                "channel:c1",
                MemoryProvenance.userSaid("userA", "run-1", Instant.now()),
                policy, store);
        System.out.println("Stored " + stored + " entries (status=PENDING_REVIEW by default for channel scope)");

        // User B can't see it yet
        List<MemoryEntry> visibleBefore = retriever.recall(List.of("channel:c1"));
        System.out.println("User B sees (before approval): " + visibleBefore.size() + " entries\n");

        // ---- T2: Admin approves ----
        System.out.println("--- T2: Admin approves ---");
        List<MemoryEntry> pending = admin.listPending("channel:c1");
        System.out.println("Pending entries: " + pending.size());

        for (MemoryEntry p : pending) {
            admin.approve(p.id());
            System.out.println("Approved: " + p.content());
        }

        // Now User B can see it
        List<MemoryEntry> visibleAfter = retriever.recall(List.of("channel:c1"));
        System.out.println("User B sees (after approval): " + visibleAfter.size() + " entries");
        System.out.println("  -> " + visibleAfter.get(0).content() + "\n");

        // ---- T3: Admin corrects a wrong fact via supersede ----
        System.out.println("--- T3: Admin corrects the fact (supersede) ---");
        System.out.println("Admin: Actually, user A is NOT allergic. Let me correct this.");

        MemoryEntry active = visibleAfter.get(0);
        MemoryEntry corrected = admin.supersede(active.id(),
                "user A is NOT allergic to peanuts (was a misunderstanding)", "admin1");

        System.out.println("Old entry status: " + store.findById(active.id()).get().status());
        System.out.println("New entry status: " + corrected.status());

        // User B now sees the corrected version
        List<MemoryEntry> finalVisible = retriever.recall(List.of("channel:c1"));
        System.out.println("User B sees (after correction): " + finalVisible.size() + " entries");
        System.out.println("  -> " + finalVisible.get(0).content());

        // Audit trail
        System.out.println("\nAudit trail (all entries in channel:c1, any status):");
        for (MemoryEntry e : admin.listByScope("channel:c1")) {
            System.out.println("  [" + e.status() + "] " + e.content()
                    + " (by " + e.provenance().sourceType() + ":" + e.provenance().actor() + ")");
        }

        System.out.println("\n=== Acceptance: channel shared, pending review, admin approve, supersede correction ===");
    }
}

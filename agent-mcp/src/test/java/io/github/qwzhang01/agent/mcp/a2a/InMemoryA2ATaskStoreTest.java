package io.github.qwzhang01.agent.mcp.a2a;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 *  pins the {@link InMemoryA2ATaskStore} contract rules the
 * HttpA2AServer relies on — upsert/find, context ordering, cross-instance
 * lease semantics, retention sweeps, live-task dedup.
 */
class InMemoryA2ATaskStoreTest {

    private static A2ATaskStore.StoredA2ATask task(String id, String contextId,
                                                   A2ATaskStatus status) {
        return new A2ATaskStore.StoredA2ATask(id, contextId, status, null,
                List.of(), null, null, null, null, null);
    }

    @Test
    void save_isUpsert_findReturnsLatest() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("t1", "c1", A2ATaskStatus.WORKING));

        A2ATaskStore.StoredA2ATask first = store.find("t1").orElseThrow();
        assertEquals(A2ATaskStatus.WORKING, first.status());

        store.save(task("t1", "c1", A2ATaskStatus.COMPLETED));
        assertEquals(A2ATaskStatus.COMPLETED, store.find("t1").orElseThrow().status());
    }

    @Test
    void findByContext_ordersNewestFirst() throws Exception {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("old", "ctx", A2ATaskStatus.COMPLETED));
        Thread.sleep(5);  // distinct updatedAt timestamps
        store.save(task("new", "ctx", A2ATaskStatus.COMPLETED));

        List<A2ATaskStore.StoredA2ATask> byContext = store.findByContext("ctx");
        assertEquals(2, byContext.size());
        assertEquals("new", byContext.get(0).taskId());
        assertEquals("old", byContext.get(1).taskId());
        assertTrue(store.findByContext("other").isEmpty());
    }

    @Test
    void acquireLease_firstWins_secondHolderSeesClaimed() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("t", "c", A2ATaskStatus.WORKING));

        assertTrue(store.acquireLease("t", "host-1", Duration.ofSeconds(60)));
        assertFalse(store.acquireLease("t", "host-2", Duration.ofSeconds(60)),
                "a live lease blocks a second claimant");

        // The holder itself can re-acquire (renewal-by-acquire path).
        assertTrue(store.acquireLease("t", "host-1", Duration.ofSeconds(60)));
    }

    @Test
    void renewLease_onlyHolderSucceeds() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("t", "c", A2ATaskStatus.WORKING));
        store.acquireLease("t", "host-1", Duration.ofSeconds(60));

        assertFalse(store.renewLease("t", "host-2", Duration.ofSeconds(60)));
        assertTrue(store.renewLease("t", "host-1", Duration.ofSeconds(60)));
        assertFalse(store.renewLease("t-unknown", "host-1", Duration.ofSeconds(60)));
    }

    @Test
    void expiredLease_selfReleases_nextHolderTakesOver() throws Exception {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("t", "c", A2ATaskStatus.WORKING));

        assertTrue(store.acquireLease("t", "dead-host", Duration.ofMillis(30)));
        Thread.sleep(80);  // let the dead host's claim expire by wall clock
        assertFalse(store.renewLease("t", "dead-host", Duration.ofSeconds(60)),
                "an expired lease cannot be renewed by its dead holder");
        assertTrue(store.acquireLease("t", "live-host", Duration.ofSeconds(60)),
                "an expired lease self-releases for the next claimant");
    }

    @Test
    void releaseLease_thenAcquireAgain() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("t", "c", A2ATaskStatus.WORKING));
        store.acquireLease("t", "host-1", Duration.ofSeconds(60));
        store.releaseLease("t", "host-1");

        assertTrue(store.acquireLease("t", "host-2", Duration.ofSeconds(60)));
        // Releasing someone else's lease is a no-op.
        store.releaseLease("t", "host-3");
        assertTrue(store.renewLease("t", "host-2", Duration.ofSeconds(60)));
    }

    @Test
    void expireOlderThan_retentionSweepRemovesTaskAndLease() throws Exception {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("stale", "c", A2ATaskStatus.COMPLETED));
        store.acquireLease("stale", "host-1", Duration.ofSeconds(60));
        Instant cutoff = Instant.now();
        Thread.sleep(5);  // fresh is created strictly AFTER the cutoff
        store.save(task("fresh", "c", A2ATaskStatus.WORKING));

        int removed = store.expireOlderThan(cutoff);

        assertEquals(1, removed);
        assertTrue(store.find("stale").isEmpty());
        assertTrue(store.find("fresh").isPresent());
        assertTrue(store.acquireLease("stale", "host-2", Duration.ofSeconds(60)),
                "the sweep must clear leases together with the task");
    }

    @Test
    void findLiveByContext_deduplicatesRunningWork() {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("done", "ctx", A2ATaskStatus.COMPLETED));
        store.save(task("live", "ctx", A2ATaskStatus.WORKING));

        Optional<A2ATaskStore.StoredA2ATask> live = store.findLiveByContext("ctx");
        assertTrue(live.isPresent());
        assertEquals("live", live.get().taskId());

        store.save(task("live", "ctx", A2ATaskStatus.COMPLETED));
        assertTrue(store.findLiveByContext("ctx").isEmpty(),
                "a terminal task is not live work anymore");
    }

    @Test
    void concurrentAcquire_exactlyOneWinner() throws Exception {
        InMemoryA2ATaskStore store = new InMemoryA2ATaskStore();
        store.save(task("race", "c", A2ATaskStatus.WORKING));

        int claimants = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger();
        Thread[] threads = new Thread[claimants];
        for (int i = 0; i < claimants; i++) {
            String holder = "host-" + i;
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException ignored) {
                }
                if (store.acquireLease("race", holder, Duration.ofSeconds(60))) {
                    winners.incrementAndGet();
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, winners.get(), "cross-instance claiming must be exclusive");
    }
}

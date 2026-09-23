package io.github.qwzhang01.agent.memory.store;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.client.ModelException.ErrorCode;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the write-side embedding decorator {@link EmbeddingMemoryStore}:
 * vectorization on write, soft failure, idempotent re-write, and full
 * delegation of the query/governance surface.
 */
class EmbeddingMemoryStoreTest {

    private static final Instant TS = Instant.parse("2026-09-10T00:00:00Z");

    /** Stub client that captures every text it was asked to embed. */
    static final class CapturingClient implements EmbeddingClient {
        final List<String> texts = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger calls = new AtomicInteger();
        final float[] vector;

        CapturingClient(float[] vector) {
            this.vector = vector;
        }

        @Override
        public float[] embed(String text) {
            calls.incrementAndGet();
            texts.add(text);
            return vector;
        }
    }


    @Test
    void write_embedsCanonicalSubjectPlusContent() {
        CapturingClient client = new CapturingClient(new float[]{1.0f, 0.0f});
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), client);

        MemoryEntry stored = store.write(entry("home-city", "lives in Shenzhen"));

        assertNotNull(stored.embedding(), "write must attach a vector");
        assertArrayEquals(new float[]{1.0f, 0.0f}, stored.embedding(), 1e-6f);
        assertEquals(1, client.calls.get(), "exactly one embed call per write");
        assertEquals(List.of("home-city: lives in Shenzhen"), client.texts,
                "canonical embed text = subject + ': ' + content");
    }

    @Test
    void write_contentOnly_usesContentAsEmbedText() {
        CapturingClient client = new CapturingClient(new float[]{1.0f});
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), client);

        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, null, "plain fact", 0.5,
                MemoryProvenance.userSaid("u1", "r1", TS), MemoryStatus.ACTIVE, TS, null));

        assertEquals(List.of("plain fact"), client.texts, "null subject degrades to content only");
    }

    @Test
    void write_idempotent_prevectorizedEntryIsPassedThrough() {
        CapturingClient client = new CapturingClient(new float[]{0.9f});
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), client);

        MemoryEntry prevectorized = entry("x", "y").withEmbedding(new float[]{0.5f});
        MemoryEntry stored = store.write(prevectorized);

        assertEquals(0, client.calls.get(), "re-write of a vectorized entry must not re-bill");
        assertArrayEquals(new float[]{0.5f}, stored.embedding(), 1e-6f);
    }

    @Test
    void write_providerFailure_softDegrades_entryStillWritten() {
        EmbeddingClient failing = text -> {
            throw new ModelException(ErrorCode.NETWORK_ERROR, "provider down");
        };
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), failing);

        MemoryEntry stored = store.write(entry("home-city", "lives in Shenzhen"));

        assertNull(stored.embedding(), "no vector on outage, but entry survives");
        assertEquals(1, store.listByScope("user:u1").size(), "entry is in the ledger");
    }

    // update path

    @Test
    void update_withStatus_keepsExistingVector_noReEmbed() {
        CapturingClient client = new CapturingClient(new float[]{0.1f, 0.2f});
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), client);

        MemoryEntry stored = store.write(entry("diet", "allergic to peanuts"));
        int callsAfterWrite = client.calls.get();
        store.update(stored.withStatus(MemoryStatus.HISTORICAL));

        assertEquals(callsAfterWrite, client.calls.get(),
                "withStatus keeps the vector through governance transitions");
        assertArrayEquals(new float[]{0.1f, 0.2f},
                store.findById(stored.id()).orElseThrow().embedding(), 1e-6f);
    }

    @Test
    void update_adminContentEdit_reEmbedsBecauseVectorIsStale() {
        CapturingClient client = new CapturingClient(new float[]{0.3f});
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(new InMemoryMemoryStore(), client);

        MemoryEntry stored = store.write(entry("diet", "allergic to peanuts"));
        // withContent() deliberately drops the embedding: edited text has a stale vector.
        store.update(stored.withContent("allergic to shellfish"));

        assertEquals(2, client.calls.get(), "content edit must re-embed the new text");
        assertEquals(List.of("diet: allergic to peanuts", "diet: allergic to shellfish"),
                client.texts);
        assertArrayEquals(new float[]{0.3f},
                store.findById(stored.id()).orElseThrow().embedding(), 1e-6f);
    }


    @Test
    void query_scopeIsolation_delegateUntouched() {
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(
                new InMemoryMemoryStore(), new CapturingClient(new float[]{1.0f}));

        store.write(entry("user:u1", "a", "secret of u1"));
        store.write(entry("user:u2", "a", "secret of u2"));

        List<MemoryEntry> u1 = store.query(MemoryQuery.builder()
                .scopes(List.of("user:u1")).build());
        assertEquals(1, u1.size());
        assertEquals("secret of u1", u1.get(0).content());
    }

    @Test
    void findActiveBySubject_delegates() {
        EmbeddingMemoryStore store = new EmbeddingMemoryStore(
                new InMemoryMemoryStore(), new CapturingClient(new float[]{1.0f}));

        store.write(entry("home-city", "lives in Shenzhen"));
        // Shanghai written later; findActiveBySubject takes the newest ACTIVE entry.
        store.write(new MemoryEntry(null, "user:u1", MemoryType.FACT, "home-city",
                "moved to Shanghai", 0.8,
                MemoryProvenance.userSaid("u1", "r1", TS.plusSeconds(60)),
                MemoryStatus.ACTIVE, TS.plusSeconds(60), null));

        var active = store.findActiveBySubject("user:u1", "home-city");
        assertEquals("moved to Shanghai", active.orElseThrow().content());
    }

    private static MemoryEntry entry(String subject, String content) {
        return entry("user:u1", subject, content);
    }

    private static MemoryEntry entry(String scope, String subject, String content) {
        return new MemoryEntry(null, scope, MemoryType.FACT, subject, content, 0.8,
                MemoryProvenance.userSaid("u1", "r1", TS),
                MemoryStatus.ACTIVE, TS, null);
    }
}

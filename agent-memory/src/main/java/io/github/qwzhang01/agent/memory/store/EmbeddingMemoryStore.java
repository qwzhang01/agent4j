package io.github.qwzhang01.agent.memory.store;

import io.github.qwzhang01.agent.core.client.EmbeddingClient;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator over any {@link MemoryStore} that vectorizes entries on write
 * (read-side embedding, step 1 of the memory roadmap).
 * <p>
 * Design follows decision 5 (decorator over inheritance): the ledger
 * (scope/subject/status/transaction semantics) stays in the delegate; this
 * layer only attaches {@code embedding} computed from {@code subject + content}.
 * A persistent delegate later stores the vectors in pgvector; the ranking side
 * consumes them through {@code HybridRankingStrategy} without knowing where
 * they came from.
 * <p>
 * Failure semantics are deliberately soft: if the embedding call fails, the
 * entry is still written with a null vector and a warn log. Read-side hybrid
 * ranking scores null-vector entries with token overlap only, so an embedding
 * outage degrades recall quality, never correctness. This mirrors the
 * "violation is a billing event, not a compile error" stance of decision 26:
 * a missing vector is an observability fact, not a pipeline failure.
 * <p>
 * Idempotency: an entry already carrying an embedding (e.g. re-written from a
 * checkpoint) is passed through untouched, so re-writes never re-bill the
 * embedding API.
 */
public class EmbeddingMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingMemoryStore.class);

    private final MemoryStore delegate;
    private final EmbeddingClient client;

    /**
     * @param delegate the ledger store (e.g. {@link InMemoryMemoryStore})
     * @param client embedding provider port; must not be null
     */
    public EmbeddingMemoryStore(MemoryStore delegate, EmbeddingClient client) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public MemoryEntry write(MemoryEntry entry) {
        if (entry.embedding() != null) {
            return delegate.write(entry);
        }
        MemoryEntry vectorized = vectorize(entry);
        return delegate.write(vectorized);
    }

    @Override
    public List<MemoryEntry> query(MemoryQuery query) {
        return delegate.query(query);
    }

    @Override
    public Optional<MemoryEntry> findActiveBySubject(String scope, String subject) {
        return delegate.findActiveBySubject(scope, subject);
    }

    @Override
    public MemoryEntry update(MemoryEntry entry) {
        if (entry.embedding() != null) {
            return delegate.update(entry);
        }
        return delegate.update(vectorize(entry));
    }

    /**
     * Vectorize both lines, then ride the delegate's atomic supersede move.
     * Without this override the interface default would decompose the move
     * into two separate delegate calls — correct for the in-memory reference,
     * but it would silently discard the single-transaction guarantee of a
     * persistent delegate (e.g. {@code PgMemoryStore}).
     */
    @Override
    public MemoryEntry supersede(MemoryEntry closedOld, MemoryEntry newEntry) {
        MemoryEntry closed = closedOld.embedding() != null ? closedOld : vectorize(closedOld);
        MemoryEntry replacement = newEntry.embedding() != null ? newEntry : vectorize(newEntry);
        return delegate.supersede(closed, replacement);
    }

    @Override
    public Optional<MemoryEntry> findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public boolean delete(String id) {
        return delegate.delete(id);
    }

    @Override
    public List<MemoryEntry> listByScope(String scope) {
        return delegate.listByScope(scope);
    }

    /**
     * Embeds {@code subject + content} and attaches the vector. A failed call
     * logs a warn and returns the entry with a null embedding — the write
     * path must not die on an embedding outage.
     */
    private MemoryEntry vectorize(MemoryEntry entry) {
        String text = entry.embedText();
        if (text == null || text.isBlank()) {
            return entry;
        }
        try {
            float[] vector = client.embed(text);
            return entry.withEmbedding(vector);
        } catch (RuntimeException e) {
            log.warn("Embedding failed for entry subject={} (id={}): {}; written without vector",
                    entry.subject(), entry.id(), e.getMessage());
            return entry;
        }
    }
}

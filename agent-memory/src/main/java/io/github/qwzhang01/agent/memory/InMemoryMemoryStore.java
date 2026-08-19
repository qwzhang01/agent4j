package io.github.qwzhang01.agent.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-memory implementation of {@link MemoryStore}.
 * <p>
 * v1 backing store for Stage 8. Uses a {@link ConcurrentHashMap} keyed by id.
 * Scope isolation is enforced in {@link #query}: entries whose scope is not in
 * the query's scope list are never returned.
 */
public class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, MemoryEntry> entries = new ConcurrentHashMap<>();

    @Override
    public MemoryEntry write(MemoryEntry entry) {
        String id = entry.id() != null ? entry.id() : UUID.randomUUID().toString();
        MemoryEntry stored = new MemoryEntry(
                id,
                entry.scope(),
                entry.type(),
                entry.subject(),
                entry.content(),
                entry.importance(),
                entry.provenance(),
                entry.status(),
                entry.createdAt() != null ? entry.createdAt() : Instant.now(),
                entry.expireAt()
        );
        entries.put(id, stored);
        return stored;
    }

    @Override
    public List<MemoryEntry> query(MemoryQuery query) {
        Stream<MemoryEntry> stream = entries.values().stream()
                // Scope isolation: only entries in the explicitly requested scopes
                .filter(e -> query.scopes().contains(e.scope()))
                // Only ACTIVE entries are retrievable (pending/rejected/superseded/excluded)
                .filter(e -> e.status() == MemoryStatus.ACTIVE)
                // TTL: lazily filter expired entries
                .filter(e -> !e.isExpired(Instant.now()));

        if (query.type() != null) {
            stream = stream.filter(e -> e.type() == query.type());
        }
        if (query.subject() != null && !query.subject().isBlank()) {
            stream = stream.filter(e -> query.subject().equals(e.subject()));
        }
        if (query.keyword() != null && !query.keyword().isBlank()) {
            String kw = query.keyword().toLowerCase();
            stream = stream.filter(e -> e.content() != null
                    && e.content().toLowerCase().contains(kw));
        }

        List<MemoryEntry> result = stream
                .sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        if (query.limit() > 0 && result.size() > query.limit()) {
            return result.subList(0, query.limit());
        }
        return result;
    }

    @Override
    public Optional<MemoryEntry> findActiveBySubject(String scope, String subject) {
        return entries.values().stream()
                .filter(e -> e.scope().equals(scope))
                .filter(e -> subject.equals(e.subject()))
                .filter(e -> e.status() == MemoryStatus.ACTIVE)
                .filter(e -> !e.isExpired(Instant.now()))
                .max(Comparator.comparing(MemoryEntry::createdAt));
    }

    @Override
    public MemoryEntry update(MemoryEntry entry) {
        if (entry.id() == null || !entries.containsKey(entry.id())) {
            throw new IllegalArgumentException("Cannot update non-existent entry: " + entry.id());
        }
        entries.put(entry.id(), entry);
        return entry;
    }

    @Override
    public Optional<MemoryEntry> findById(String id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public boolean delete(String id) {
        return entries.remove(id) != null;
    }

    @Override
    public List<MemoryEntry> listByScope(String scope) {
        return entries.values().stream()
                .filter(e -> e.scope().equals(scope))
                .sorted(Comparator.comparing(MemoryEntry::createdAt).reversed())
                .toList();
    }
}

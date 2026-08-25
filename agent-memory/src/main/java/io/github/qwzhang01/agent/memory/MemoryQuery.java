package io.github.qwzhang01.agent.memory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Query criteria for retrieving memories.
 * <p>
 * Retrieval is scope-bounded by design: the caller must explicitly list which
 * scopes are visible for the current context. The store will never return
 * entries outside this list (Stage 8 D3 isolation).
 *
 * @param scopes  scopes to search within (e.g. [user:u1, channel:c1])
 * @param type    optional type filter (null = any)
 * @param subject optional exact subject filter (null = any)
 * @param keyword optional keyword filter matched against content (null/blank = any)
 * @param limit   max results (0 or negative = no limit)
 * @param dueFrom inclusive lower bound on {@link MemoryEntry#dueAt()} (null = no min)
 * @param dueTo   inclusive upper bound on {@link MemoryEntry#dueAt()} (null = no max)
 */
public record MemoryQuery(
        List<String> scopes,
        MemoryType type,
        String subject,
        String keyword,
        int limit,
        Instant dueFrom,
        Instant dueTo
) {

    public static Builder builder() {
        return new Builder();
    }

    /**
     * {@code true} when the caller asked for a due-time window.
     * Entries with a null {@code dueAt} are excluded from that window.
     */
    public boolean hasDueWindow() {
        return dueFrom != null || dueTo != null;
    }

    public static final class Builder {
        private List<String> scopes;
        private MemoryType type;
        private String subject;
        private String keyword;
        private int limit;
        private Instant dueFrom;
        private Instant dueTo;

        public Builder scopes(List<String> scopes) {
            this.scopes = scopes;
            return this;
        }

        public Builder scopes(MemoryScope... scopes) {
            this.scopes = java.util.stream.Stream.of(scopes).map(MemoryScope::value).toList();
            return this;
        }

        public Builder type(MemoryType type) {
            this.type = type;
            return this;
        }

        public Builder subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Builder keyword(String keyword) {
            this.keyword = keyword;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder dueFrom(Instant dueFrom) {
            this.dueFrom = dueFrom;
            return this;
        }

        public Builder dueTo(Instant dueTo) {
            this.dueTo = dueTo;
            return this;
        }

        public Builder dueBetween(Instant from, Instant to) {
            this.dueFrom = from;
            this.dueTo = to;
            return this;
        }

        public MemoryQuery build() {
            Objects.requireNonNull(scopes, "scopes must not be null");
            return new MemoryQuery(scopes, type, subject, keyword, limit, dueFrom, dueTo);
        }
    }
}

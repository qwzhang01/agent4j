package io.github.qwzhang01.agent.memory;

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
 */
public record MemoryQuery(
        List<String> scopes,
        MemoryType type,
        String subject,
        String keyword,
        int limit
) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> scopes;
        private MemoryType type;
        private String subject;
        private String keyword;
        private int limit;

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

        public MemoryQuery build() {
            Objects.requireNonNull(scopes, "scopes must not be null");
            return new MemoryQuery(scopes, type, subject, keyword, limit);
        }
    }
}

package io.github.qwzhang01.agent.memory;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * A single unit of long-term memory.
 * <p>
 * Unlike a raw {@code ChatMessage}, a MemoryEntry is structured and governable:
 * it carries type, subject (for conflict detection), provenance (for traceability),
 * status (for the review lifecycle) and importance (for write-gating).
 * <p>
 * Stage 8 D2: memory is structured entries, not raw messages.
 *
 * @param id          unique identifier
 * @param scope       namespace (e.g. "user:u1", "channel:c1")
 * @param type        what kind of memory this is
 * @param subject     topic key used for conflict detection / supersede (e.g. "dietary-restriction")
 * @param content     the actual memory text (e.g. "allergic to peanuts")
 * @param importance  0.0 ~ 1.0, used by the write-gate policy
 * @param provenance  where this memory came from
 * @param status      lifecycle status
 * @param createdAt   when it was first written
 * @param expireAt    TTL deadline (null = permanent)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemoryEntry(
        String id,
        String scope,
        MemoryType type,
        String subject,
        String content,
        double importance,
        MemoryProvenance provenance,
        MemoryStatus status,
        Instant createdAt,
        Instant expireAt
) {
    // ============ With Methods (for governance transitions) ============

    public MemoryEntry withStatus(MemoryStatus newStatus) {
        return new MemoryEntry(id, scope, type, subject, content, importance,
                provenance, newStatus, createdAt, expireAt);
    }

    public MemoryEntry withContent(String newContent) {
        return new MemoryEntry(id, scope, type, subject, newContent, importance,
                provenance, status, createdAt, expireAt);
    }

    /**
     * Whether this entry is still within its TTL window.
     */
    public boolean isExpired(Instant now) {
        return expireAt != null && !now.isBefore(expireAt);
    }
}

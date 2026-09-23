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
 * <p>
 * <b>Bi-temporal timestamps (reconciliation step 2)</b>: the entry carries two
 * axes of time. Business axis ({@code validFrom}/{@code validAt}) answers
 * "when was this fact true in the world" (event time). System axis
 * ({@code createdAt}/{@code invalidAt}) answers "when did the ledger record /
 * close this line" (processing time). When a supersede closes an old entry,
 * its {@code validAt} is set to the NEW fact's business time (the user moved
 * last week, not this Monday when the system learned it), and its
 * {@code invalidAt} is set to now (the ledger closes the line now). The two
 * must never share values, or interval queries answer "when did I move"
 * with the recording date.
 *
 * @param id          unique identifier
 * @param scope       namespace (e.g. "user:u1", "channel:c1")
 * @param type        what kind of memory this is
 * @param subject     topic key used for conflict detection / supersede (e.g. "dietary-restriction")
 * @param content     the actual memory text (e.g. "allergic to peanuts")
 * @param importance  0.0 ~ 1.0; write-gate threshold and context-recall rank
 * @param provenance  where this memory came from
 * @param status      lifecycle status
 * @param createdAt   when it was first written (system axis: recording time)
 * @param expireAt    TTL deadline (null = permanent); after this the entry is not retrievable
 * @param dueAt       optional due time with no built-in meaning (null = none).
 *                    Hosts use it for their own scans; this module does not schedule jobs.
 * @param lifecycle   how this entry relates to an older same-subject entry it replaces:
 *                    {@link MemoryLifecycle#EVOLVE} (old content was once true, then changed)
 *                    or {@link MemoryLifecycle#CONFLICT} (old content was wrong from the start).
 *                    Null = not judged; the write path then treats it as CONFLICT.
 * @param embedding   optional semantic vector of {@code subject + content}, computed
 *                    on write by {@code EmbeddingMemoryStore} (read-side step 1).
 *                    Null on legacy entries; hybrid ranking degrades those to
 *                    token-overlap scoring instead of crashing.
 * @param validFrom   business axis: when this fact became true in the world (event time).
 *                    Null = unknown; treat as "true since first recorded" for interval
 *                    queries. Parsed from the extractor's optional {@code validFrom}
 *                    output when the conversation states a business time (e.g. "I
 *                    moved last week"); null otherwise.
 * @param validAt     business axis: when this fact stopped being true in the world.
 *                    Null = still true. Set by the supersede path to the REPLACING
 *                    entry's {@code validFrom} (new fact's business start) — not to
 *                    wall-clock now, so "when did I move" answers with the business
 *                    date, and the old/new intervals on the business axis never overlap.
 * @param invalidAt   system axis: when the ledger closed this line (null = open line).
 *                    Set to now when a supersede/archive transition is applied. Audit
 *                    queries filter on this to answer "what did the system know at
 *                    time T" (createdAt ≤ T &lt; invalidAt).
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
        Instant expireAt,
        Instant dueAt,
        MemoryLifecycle lifecycle,
        float[] embedding,
        Instant validFrom,
        Instant validAt,
        Instant invalidAt
) {
    /** Backward-compatible constructor: no due time, no lifecycle, no embedding, no timestamps. */
    public MemoryEntry(String id, String scope, MemoryType type, String subject, String content,
                       double importance, MemoryProvenance provenance, MemoryStatus status,
                       Instant createdAt, Instant expireAt) {
        this(id, scope, type, subject, content, importance, provenance, status,
                createdAt, expireAt, null, null, null, null, null, null);
    }

    /** Backward-compatible constructor: no lifecycle, no embedding, no timestamps. */
    public MemoryEntry(String id, String scope, MemoryType type, String subject, String content,
                       double importance, MemoryProvenance provenance, MemoryStatus status,
                       Instant createdAt, Instant expireAt, Instant dueAt) {
        this(id, scope, type, subject, content, importance, provenance, status,
                createdAt, expireAt, dueAt, null, null, null, null, null);
    }

    /** Backward-compatible constructor: no embedding, no timestamps. */
    public MemoryEntry(String id, String scope, MemoryType type, String subject, String content,
                       double importance, MemoryProvenance provenance, MemoryStatus status,
                       Instant createdAt, Instant expireAt, Instant dueAt,
                       MemoryLifecycle lifecycle) {
        this(id, scope, type, subject, content, importance, provenance, status,
                createdAt, expireAt, dueAt, lifecycle, null, null, null, null);
    }

    /** Backward-compatible constructor: no timestamps. */
    public MemoryEntry(String id, String scope, MemoryType type, String subject, String content,
                       double importance, MemoryProvenance provenance, MemoryStatus status,
                       Instant createdAt, Instant expireAt, Instant dueAt,
                       MemoryLifecycle lifecycle, float[] embedding) {
        this(id, scope, type, subject, content, importance, provenance, status,
                createdAt, expireAt, dueAt, lifecycle, embedding, null, null, null);
    }

    // With Methods (for governance transitions)

    public MemoryEntry withStatus(MemoryStatus newStatus) {
        return new MemoryEntry(id, scope, type, subject, content, importance,
                provenance, newStatus, createdAt, expireAt, dueAt, lifecycle, embedding,
                validFrom, validAt, invalidAt);
    }

    public MemoryEntry withContent(String newContent) {
        return new MemoryEntry(id, scope, type, subject, newContent, importance,
                provenance, status, createdAt, expireAt, dueAt, lifecycle, null,
                validFrom, validAt, invalidAt);
    }

    public MemoryEntry withDueAt(Instant newDueAt) {
        return new MemoryEntry(id, scope, type, subject, content, importance,
                provenance, status, createdAt, expireAt, newDueAt, lifecycle, embedding,
                validFrom, validAt, invalidAt);
    }

    /**
     * Copy of this entry with a computed embedding attached.
     * Used by {@code EmbeddingMemoryStore} on write; does not mutate the original.
     */
    public MemoryEntry withEmbedding(float[] newEmbedding) {
        return new MemoryEntry(id, scope, type, subject, content, importance,
                provenance, status, createdAt, expireAt, dueAt, lifecycle, newEmbedding,
                validFrom, validAt, invalidAt);
    }

    // Bi-temporal transitions (reconciliation step 2)

    /**
     * Close this entry on both axes: business axis ends at the replacing fact's
     * business start ({@code newValidAt}, the new entry's {@code validFrom}), system
     * axis ends at {@code closedAt} (now). Status change goes through
     * {@link #withStatus} separately; this method only stamps the axes.
     * <p>
     * Callers must pass {@code newValidAt} = replacing entry's {@code validFrom}
     * (falling back to its {@code createdAt} when the replacing fact carries no
     * business time), never wall-clock now — see class javadoc.
     */
    public MemoryEntry closedAs(MemoryStatus newStatus, Instant newValidAt, Instant closedAt) {
        return new MemoryEntry(id, scope, type, subject, content, importance,
                provenance, newStatus, createdAt, expireAt, dueAt, lifecycle, embedding,
                validFrom, newValidAt, closedAt);
    }

    /**
     * Whether this entry is still within its TTL window.
     */
    public boolean isExpired(Instant now) {
        return expireAt != null && !now.isBefore(expireAt);
    }

    /**
     * Canonical text to embed for semantic retrieval: subject + content.
     * <p>
     * Single definition shared by the write side ({@code EmbeddingMemoryStore})
     * and the read side ({@code HybridRankingStrategy}) so both always embed
     * the same string — otherwise query and entry vectors live in slightly
     * different semantic spaces and cosine similarity drifts. Returns null
     * when both parts are blank.
     */
    public String embedText() {
        String subject = this.subject == null ? "" : this.subject.trim();
        String content = this.content == null ? "" : this.content.trim();
        if (subject.isEmpty() && content.isEmpty()) {
            return null;
        }
        if (subject.isEmpty()) {
            return content;
        }
        if (content.isEmpty()) {
            return subject;
        }
        return subject + ": " + content;
    }
}

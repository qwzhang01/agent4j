package io.github.qwzhang01.agent.enterprise.knowledge;

import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryScope;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * One knowledge item of a tenant's knowledge base (Stage 15 M15.2, D5
 * "knowledge is memory").
 * <p>
 * Knowledge is the business INPUT of the enterprise answer flow: admin-curated
 * policy documents, FAQs and product facts that the model must retrieve before
 * answering (RAG). This differs from conversation-derived memories (Stage 8),
 * which are the SEDIMENT of interactions.
 * <p>
 * Storage projection: a KnowledgeEntry persists as a
 * {@code MemoryEntry(type=KNOWLEDGE, scope=tenant:{tid})} - the same store,
 * the same scope-whitelist isolation, one mechanism instead of two systems
 * (third application of the "same mechanism" philosophy, after Stage 12
 * channel-shared memory and the Stage 14 workflow trajectory adapter).
 * <p>
 * Honest v1 boundary: {@code source} and {@code tags} are import-side audit
 * metadata only. {@code MemoryEntry} has no free-metadata slot, so they are
 * NOT persisted and are NOT reconstructed on retrieval
 * ({@link #fromMemoryEntry} returns empty) - missing rather than fabricated,
 * per the Stage 14 D5 discipline. They join the store when MemoryEntry grows
 * a custom field (v2).
 *
 * @param title   human-readable title; stored as the MemoryEntry subject
 * @param content the knowledge text; the retrieval corpus (keyword-matched)
 * @param source  where this item came from (e.g. "policy.pdf", "FAQ#12");
 *                import-side metadata, not persisted in v1
 * @param tags    optional categorization labels; import-side metadata,
 *                not persisted in v1
 */
public record KnowledgeEntry(
        String title,
        String content,
        String source,
        Set<String> tags
) {

    /** Default max results when a caller does not specify topK. */
    public static final int DEFAULT_TOP_K = 3;

    public KnowledgeEntry {
        requireText(title, "title");
        requireText(content, "content");
        tags = tags == null ? Set.of() : Set.copyOf(tags);
    }

    // ============ Convenience Factory ============

    /**
     * A minimal entry: title + content, no source, no tags.
     */
    public static KnowledgeEntry of(String title, String content) {
        return new KnowledgeEntry(title, content, null, null);
    }

    // ============ Storage Projection ============

    /**
     * Project this entry into its storage form under the given tenant scope.
     * <p>
     * Admin-ingested knowledge enters the store as ACTIVE immediately (not
     * PENDING_REVIEW): it is a controlled import, not dialog sediment - the
     * review already happened when the admin chose to ingest.
     *
     * @param tenantId the tenant this knowledge belongs to (isolation boundary)
     * @param adminId  who performed the ingest (provenance actor)
     */
    public MemoryEntry toMemoryEntry(String tenantId, String adminId) {
        return new MemoryEntry(
                null,
                MemoryScope.tenant(tenantId).value(),
                MemoryType.KNOWLEDGE,
                title,
                content,
                1.0,
                MemoryProvenance.adminEdit(adminId, Instant.now()),
                MemoryStatus.ACTIVE,
                Instant.now(),
                null
        );
    }

    /**
     * Reconstruct the domain view from storage. Source/tags come back empty
     * (see class javadoc for the honest v1 boundary).
     */
    public static KnowledgeEntry fromMemoryEntry(MemoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        return new KnowledgeEntry(entry.subject(), entry.content(), null, null);
    }

    // ============ Helpers ============

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

package io.github.qwzhang01.agent.enterprise.knowledge;

import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryQuery;
import io.github.qwzhang01.agent.memory.MemoryScope;
import io.github.qwzhang01.agent.memory.MemoryStore;
import io.github.qwzhang01.agent.memory.MemoryType;

import java.util.List;
import java.util.Objects;

/**
 * Tenant-scoped knowledge base facade (Stage 15 M15.2, D5).
 * <p>
 * This class deliberately builds on the Stage 8 store instead of adding a
 * second storage system: knowledge entries are {@code MemoryEntry} rows of
 * {@code type=KNOWLEDGE} under {@code tenant:{id}} scopes. Everything the
 * store already guarantees is inherited for free - scope-whitelist isolation
 * (cross-tenant leakage is impossible by mechanism, proven by M15.1 tests),
 * TTL, and admin tooling.
 * <p>
 * Isolation note: the tenant boundary is enforced by the store, NOT by this
 * class. {@link #search} always queries with exactly one tenant scope; the
 * store never returns entries outside the requested scope list. A
 * KnowledgeBase shared across tenants (one store) is therefore safe by
 * construction - the standard deployment shape.
 */
public final class KnowledgeBase {

    private final MemoryStore store;

    public KnowledgeBase(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    // ============ Ingest ============

    /**
     * Bulk-import knowledge entries for a tenant (admin operation).
     * <p>
     * Entries enter as ACTIVE immediately - the controlled-import review
     * already happened when the admin curated the list (contrast with
     * channel memories, which go through PENDING_REVIEW).
     *
     * @param tenantId the tenant whose knowledge base this belongs to
     * @param entries  the entries to import (null/empty tolerated as no-op)
     * @param adminId  who is importing (recorded in provenance)
     */
    public void ingest(String tenantId, List<KnowledgeEntry> entries, String adminId) {
        requireTenantId(tenantId);
        Objects.requireNonNull(adminId, "adminId must not be null");
        if (entries == null || entries.isEmpty()) {
            return;
        }
        for (KnowledgeEntry entry : entries) {
            store.write(entry.toMemoryEntry(tenantId, adminId));
        }
    }

    // ============ Search ============

    /**
     * Keyword-search the tenant's knowledge (v1 retrieval: case-insensitive
     * content match; vector embedding is v2 - the interface shape does not
     * change when the retriever implementation is swapped).
     *
     * @param tenantId the tenant whose knowledge is searched - and ONLY that
     *                 tenant's (scope whitelist, D3)
     * @param query    keyword; null/blank returns the newest entries unfiltered
     * @param topK     max results; {@code <=} 0 means {@link KnowledgeEntry#DEFAULT_TOP_K}
     * @return matching entries, newest first; empty list when nothing matches
     */
    public List<KnowledgeEntry> search(String tenantId, String query, int topK) {
        requireTenantId(tenantId);
        int limit = topK > 0 ? topK : KnowledgeEntry.DEFAULT_TOP_K;
        List<MemoryEntry> hits = store.query(MemoryQuery.builder()
                .scopes(List.of(MemoryScope.tenant(tenantId).value()))
                .type(MemoryType.KNOWLEDGE)
                .keyword(query)
                .limit(limit)
                .build());
        return hits.stream()
                .map(KnowledgeEntry::fromMemoryEntry)
                .toList();
    }

    // ============ Introspection ============

    /**
     * Number of knowledge entries currently stored for the tenant (admin
     * introspection; counts only KNOWLEDGE-type entries in the tenant scope).
     */
    public long count(String tenantId) {
        requireTenantId(tenantId);
        return store.query(MemoryQuery.builder()
                        .scopes(List.of(MemoryScope.tenant(tenantId).value()))
                        .type(MemoryType.KNOWLEDGE)
                        .limit(0)
                        .build())
                .size();
    }

    // ============ Helpers ============

    private static void requireTenantId(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
    }
}

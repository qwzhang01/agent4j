package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.redact.RedactionPolicy;
import io.github.qwzhang01.agent.core.redact.SecretMasker;
import io.github.qwzhang01.agent.core.run.RunContext;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Stage 5.2: governed access facade over {@link MemoryStore}.
 * <p>
 * The raw store API trusts its caller completely: any code holding a
 * {@code MemoryStore} reference can query any scope with no identity, no
 * audit trail, no redaction. In production that is not acceptable — memory
 * holds user-remembered facts (PII, secrets pasted into chats), and the
 * roadmap demands every access be attributable and every consumer-visible
 * content be redactable.
 * <p>
 * This facade enforces, on every call:
 * <ul>
 *   <li><b>Identity</b> — the scope whitelist is derived from the caller's
 *       {@link RunContext} (tenant / user / channel), never accepted as a
 *       free-form list. A tool cannot widen its own visibility by passing
 *       a bigger scope list.</li>
 *   <li><b>Audit</b> — every READ / WRITE emits a {@link MemoryAccessAuditRecord}
 *       (who / when / which scopes / how many results) to the configured sink.</li>
 *   <li><b>Purpose</b> — callers must declare a purpose string per access;
 *       blank purposes are rejected fail-loud. Purpose lands in the audit
 *       record, making "why was this data touched" answerable.</li>
 *   <li><b>Redaction</b> — consumer-visible content goes through the
 *       {@link RedactionPolicy}; the default is {@link RedactionPolicy#rawPlusMasked}
 *       (raw stays in the ledger for admin recall, consumers get masked copy).</li>
 * </ul>
 * Governance operations that mutate data (approve / edit / ttl / purge)
 * stay on {@link MemoryAdmin} — this facade covers the runtime read/write
 * path that agents and tools actually sit on.
 * <p>
 * <b>Deletion propagation (roadmap 5.2).</b> {@link #purgeForUser} /
 * {@link #purgeForTenant} hard-delete every entry in the affected scopes
 * and return a {@link DeletionPropagation} describing exactly what was
 * removed — not just "row gone", but which scopes, how many entries, at
 * what time — so GDPR-style deletion can be verified instead of trusted.
 */
public class MemoryGovernance {

    private final MemoryStore store;
    private final RedactionPolicy readPolicy;
    private final Consumer<MemoryAccessAuditRecord> auditSink;

    /**
     * @param store     the underlying store (required)
     * @param readPolicy redaction applied to consumer-visible content on reads
     *                  (null = {@link RedactionPolicy#rawPlusMasked} default)
     * @param auditSink consumer of audit records (null = no-op, tests only;
     *                  production should always wire a sink)
     */
    public MemoryGovernance(MemoryStore store,
                            RedactionPolicy readPolicy,
                            Consumer<MemoryAccessAuditRecord> auditSink) {
        this.store = Objects.requireNonNull(store, "store");
        this.readPolicy = readPolicy != null ? readPolicy : RedactionPolicy.rawPlusMasked(SecretMasker.withDefaults());
        this.auditSink = auditSink != null ? auditSink : r -> { };
    }

    // ============ Scope derivation (identity-bound) ============

    /**
     * Derive the scope whitelist from a run context. The caller's identity
     * determines what is visible: tenant + user scopes always, channel scope
     * when the run carries one, agent scope when the run names an agent.
     * A context with neither tenant nor user yields an empty list — such
     * runs see no governed memory at all.
     */
    public static List<String> scopesFor(RunContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        Set<String> scopes = new LinkedHashSet<>();
        if (ctx.tenantId() != null && !ctx.tenantId().isBlank()) {
            scopes.add(MemoryScope.tenant(ctx.tenantId()).value());
        }
        if (ctx.userId() != null && !ctx.userId().isBlank()) {
            scopes.add(MemoryScope.user(ctx.userId()).value());
        }
        if (ctx.channelId() != null && !ctx.channelId().isBlank()) {
            scopes.add(MemoryScope.channel(ctx.channelId()).value());
        }
        if (ctx.agentId() != null && !ctx.agentId().isBlank()) {
            scopes.add(MemoryScope.agent(ctx.agentId()).value());
        }
        return List.copyOf(scopes);
    }

    // ============ Governed read ============

    /**
     * Governed query. Scopes come from the run context (never from the query
     * object), the result content is redacted per policy, and one audit
     * record is emitted.
     *
     * @return redacted entries; masked=true when the policy masked anything
     */
    public MemoryReadResult query(MemoryQuery query, RunContext ctx, String purpose) {
        Objects.requireNonNull(query, "query");
        requirePurpose(purpose);
        List<String> scopes = scopesFor(ctx);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "RunContext carries no identity (no tenant/user/channel/agent): governed memory is not visible");
        }
        MemoryQuery scoped = restrictScopes(query, scopes);
        List<MemoryEntry> raw = store.query(scoped);
        List<MemoryEntry> redacted = raw.stream()
                .map(this::redactEntry)
                .toList();
        boolean masked = raw.stream().anyMatch(e -> !Objects.equals(e.content(), readPolicy.apply(e.content())));
        auditSink.accept(MemoryAccessAuditRecord.read(
                ctx.tenantId(), ctx.userId(), ctx.runId(),
                scopes, purpose, raw.size(), masked, Instant.now()));
        return new MemoryReadResult(redacted, masked, scopes);
        }

    /**
     * The result of a governed read: redacted entries + whether masking
     * actually changed anything + the scope whitelist used.
     */
    public record MemoryReadResult(List<MemoryEntry> entries, boolean masked, List<String> scopes) {
    }

    private MemoryEntry redactEntry(MemoryEntry e) {
        String maskedContent = readPolicy.apply(e.content());
        if (Objects.equals(maskedContent, e.content())) {
            return e; // nothing maskable or null content: passthrough, zero-copy
        }
        return e.withContent(maskedContent);
    }

    // ============ Governed write ============

    /**
     * Governed write. The entry's scope must fall inside the run context's
     * whitelist (a tool cannot write into another tenant's namespace), and
     * the write is audited with the actor identity.
     */
    public MemoryEntry write(MemoryEntry entry, RunContext ctx, String purpose) {
        Objects.requireNonNull(entry, "entry");
        requirePurpose(purpose);
        List<String> scopes = scopesFor(ctx);
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException(
                    "RunContext carries no identity: governed writes require one");
        }
        if (entry.scope() == null || !scopes.contains(entry.scope())) {
            throw new IllegalArgumentException(
                    "Entry scope " + entry.scope() + " is outside the caller's scope whitelist " + scopes);
        }
        MemoryEntry stored = store.write(entry);
        auditSink.accept(MemoryAccessAuditRecord.write(
                ctx.tenantId(), ctx.userId(), ctx.runId(),
                scopes, purpose, 1, Instant.now()));
        return stored;
    }

    // ============ Deletion propagation ============

    /**
     * Hard-delete every entry in every scope of one user across tenants —
     * GDPR "right to be forgotten" propagation. Returns what was removed.
     */
    public DeletionPropagation purgeForUser(RunContext ctx, String userId) {
        Objects.requireNonNull(ctx, "ctx");
        List<String> userScopes = List.of(MemoryScope.user(userId).value());
        List<String> removedIds = new ArrayList<>();
        for (String scope : userScopes) {
            store.listByScope(scope).forEach(e -> {
                if (store.delete(e.id())) {
                    removedIds.add(e.id());
                }
            });
        }
        return new DeletionPropagation(userId, null, userScopes, removedIds, Instant.now());
    }

    /**
     * Hard-delete every entry in one tenant namespace. Returns what was removed.
     */
    public DeletionPropagation purgeForTenant(RunContext ctx, String tenantId) {
        Objects.requireNonNull(ctx, "ctx");
        List<String> tenantScopes = List.of(MemoryScope.tenant(tenantId).value());
        List<String> removedIds = new ArrayList<>();
        for (String scope : tenantScopes) {
            store.listByScope(scope).forEach(e -> {
                if (store.delete(e.id())) {
                    removedIds.add(e.id());
                }
            });
        }
        return new DeletionPropagation(null, tenantId, tenantScopes, removedIds, Instant.now());
    }

    /**
     * Stage 5.2 deletion propagation record: not just "row gone" but which
     * scopes were swept, which entry ids were removed, and when — deletion
     * becomes verifiable instead of trusted.
     */
    public record DeletionPropagation(
            String userId,
            String tenantId,
            List<String> scopes,
            List<String> removedEntryIds,
            Instant at) {
    }

    // ============ Internals ============

    private static void requirePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose must not be null/blank (roadmap 5.2: no purposeless access)");
        }
    }

    private static MemoryQuery restrictScopes(MemoryQuery query, List<String> scopes) {
        // intersect the query's own scopes with the identity whitelist
        List<String> requested = query.scopes();
        List<String> effective;
        if (requested == null || requested.isEmpty()) {
            effective = scopes;
        } else {
            effective = requested.stream().filter(scopes::contains).toList();
            if (effective.isEmpty()) {
                throw new IllegalArgumentException(
                        "Query scopes " + requested + " are outside the caller's whitelist " + scopes);
                }
        }
        return new MemoryQuery(effective, query.type(), query.subject(), query.keyword(),
                query.limit(), query.dueFrom(), query.dueTo(), query.statuses());
    }
}

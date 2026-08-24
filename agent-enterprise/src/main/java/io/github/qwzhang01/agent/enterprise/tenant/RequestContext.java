package io.github.qwzhang01.agent.enterprise.tenant;

import io.github.qwzhang01.agent.memory.MemoryScope;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The identity snapshot of one request (Stage 15 M15.1).
 * <p>
 * This is the single carrier that flows through the whole enterprise chain:
 * permission checks (M15.3), audit attribution, memory/knowledge retrieval
 * scopes and cost accounting all read from the same context. It is passed
 * explicitly (per-request assembly, blueprint D2) - never via ThreadLocal.
 * <p>
 * {@link #memoryScopes()} is the SSOT of the retrieval whitelist: it lists
 * exactly {@code tenant:{tid}} and {@code user:{uid}} - the store's scope
 * whitelist (Stage 8 D3) then guarantees that nothing outside this list can
 * ever be retrieved. Isolation is mechanism, not convention.
 *
 * @param tenant    the tenant the request belongs to
 * @param user      the authenticated user issuing the request
 * @param sessionId conversation/session identifier (null = auto-generated)
 */
public record RequestContext(
        Tenant tenant,
        User user,
        String sessionId
) {

    public RequestContext {
        Objects.requireNonNull(tenant, "tenant must not be null");
        Objects.requireNonNull(user, "user must not be null");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = "sess-" + UUID.randomUUID();
        }
    }

    // ============ Derived Accessors ============

    /**
     * The retrieval whitelist for this request: tenant scope (shared knowledge
     * of the tenant) plus the user's own scope (personal memories). Feeds
     * {@code MemoryQuery.scopes} directly.
     */
    public List<String> memoryScopes() {
        return List.of(
                MemoryScope.tenant(tenant.tenantId()).value(),
                MemoryScope.user(user.userId()).value()
        );
    }

    /**
     * Audit attribution of the requesting actor (e.g. "user:u-alice").
     * Combined with the service identity (Stage 12, wired in v2) this forms
     * the dual attribution "executed by svc:x on behalf of user:y".
     */
    public String actor() {
        return "user:" + user.userId();
    }

    /**
     * Convenience accessor for the tenant id.
     */
    public String tenantId() {
        return tenant.tenantId();
    }

    /**
     * Convenience accessor for the user id.
     */
    public String userId() {
        return user.userId();
    }
}

package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.AuditLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The enterprise audit trail: a shared ledger plus per-request attribution
 * views (Stage 15 M15.3, D4).
 * <p>
 * Two roles in one class, mirroring {@code KnowledgeBase}'s tenant binding:
 * <ul>
 *   <li><b>Assembly-level ledger</b> (this instance): accumulates
 *       {@link EnterpriseAuditEvent}s across all requests; answers enterprise
 *       questions - byTenant, byUser ("what did this employee make the Agent
 *       do"), byTool, all</li>
 *   <li><b>Request-scoped view</b> ({@link #forRequest}): an
 *       {@link AuditLogger} bound to one {@link RequestContext} that the
 *       {@code GovernedToolExecutor} calls back into; each raw
 *       {@link AuditEvent} gets attribution completed (tenant + user +
 *       agentName) before it enters the ledger. The existing governance chain
 *       is unchanged - it still emits plain Stage 9 events; attribution is
 *       added where the enterprise layer can see it</li>
 * </ul>
 * Denials are recorded exactly like executions - "who was blocked trying
 * what" is a security signal (Stage 9 D6), and with attribution it becomes
 * "which tenant's which user was blocked".
 */
public final class EnterpriseAuditTrail {

    private final List<EnterpriseAuditEvent> ledger = new CopyOnWriteArrayList<>();

    // ============ Request-Scoped View ============

    /**
     * Create the request-scoped {@link AuditLogger} bound to one context.
     * Wire it into {@code GovernedToolExecutor.Builder.auditLogger(...)} -
     * the governance chain then produces attributed events with zero changes.
     *
     * @param ctx       the request context providing tenant/user attribution
     * @param agentName the executing agent's assembly name (recorded on every
     *                  event of this request)
     */
    public AuditLogger forRequest(RequestContext ctx, String agentName) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (agentName == null || agentName.isBlank()) {
            throw new IllegalArgumentException("agentName must not be blank");
        }
        return new RequestAuditLogger(ctx, agentName);
    }

    // ============ Ledger Queries ============

    /**
     * All attributed events, oldest first.
     */
    public List<EnterpriseAuditEvent> all() {
        return List.copyOf(ledger);
    }

    /**
     * Every event attributed to a tenant - the compliance cut
     * ("show me everything that happened inside acme this month").
     */
    public List<EnterpriseAuditEvent> byTenant(String tenantId) {
        return filter(e -> e.tenantId().equals(tenantId));
    }

    /**
     * Every event a user triggered (or was denied) - the HR cut
     * ("what did this employee make the Agent do").
     */
    public List<EnterpriseAuditEvent> byUser(String userId) {
        return filter(e -> e.userId().equals(userId));
    }

    /**
     * Every event involving one tool - the incident cut
     * ("who called refund_order, and what happened").
     */
    public List<EnterpriseAuditEvent> byTool(String toolName) {
        return filter(e -> e.toolName().equals(toolName));
    }

    /**
     * Number of events currently in the ledger.
     */
    public int size() {
        return ledger.size();
    }

    // ============ Helpers ============

    private List<EnterpriseAuditEvent> filter(java.util.function.Predicate<EnterpriseAuditEvent> p) {
        List<EnterpriseAuditEvent> out = new ArrayList<>();
        for (EnterpriseAuditEvent e : ledger) {
            if (p.test(e)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * The request-scoped view handed to the governance chain. Implements the
     * full {@link AuditLogger} interface so it is a drop-in replacement for
     * {@code InMemoryAuditLogger}; the query methods are scoped to this
     * request's own events.
     */
    private final class RequestAuditLogger implements AuditLogger {

        private final RequestContext ctx;
        private final String agentName;
        private final List<EnterpriseAuditEvent> ownEvents = new ArrayList<>();

        private RequestAuditLogger(RequestContext ctx, String agentName) {
            this.ctx = ctx;
            this.agentName = agentName;
        }

        @Override
        public void log(AuditEvent event) {
            Objects.requireNonNull(event, "event must not be null");
            EnterpriseAuditEvent attributed = new EnterpriseAuditEvent(
                    event, ctx.tenantId(), ctx.userId(), agentName);
            ledger.add(attributed);
            synchronized (ownEvents) {
                ownEvents.add(attributed);
            }
        }

        @Override
        public List<AuditEvent> getAll() {
            return snapshot().stream().map(EnterpriseAuditEvent::event).toList();
        }

        @Override
        public List<AuditEvent> getByRun(String runId) {
            return snapshot().stream()
                    .map(EnterpriseAuditEvent::event)
                    .filter(e -> runId != null && runId.equals(e.runId()))
                    .toList();
        }

        @Override
        public List<AuditEvent> getByTool(String toolName) {
            return snapshot().stream()
                    .map(EnterpriseAuditEvent::event)
                    .filter(e -> toolName != null && toolName.equals(e.toolName()))
                    .toList();
        }

        private List<EnterpriseAuditEvent> snapshot() {
            synchronized (ownEvents) {
                return List.copyOf(ownEvents);
            }
        }
    }
}

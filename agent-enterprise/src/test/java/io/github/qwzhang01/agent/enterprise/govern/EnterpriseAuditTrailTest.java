package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;
import io.github.qwzhang01.agent.enterprise.tenant.User;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 15 M15.3: audit attribution - "who made the Agent do what".
 */
class EnterpriseAuditTrailTest {

    private static final ToolCall REFUND_CALL = new ToolCall("c1", "refund_order", null);
    private static final ToolCall QUERY_CALL = new ToolCall("c2", "query_order", null);

    private EnterpriseAuditTrail trail;
    private RequestContext aliceCtx;
    private RequestContext bobCtx;
    private RequestContext carolCtx;

    @BeforeEach
    void setUp() {
        trail = new EnterpriseAuditTrail();
        Tenant acme = Tenant.active("acme", "Acme Corp");
        Tenant globex = Tenant.active("globex", "Globex Inc");
        aliceCtx = new RequestContext(acme,
                new User("u-alice", "acme", "Alice", Set.of(User.ROLE_CSR)), null);
        bobCtx = new RequestContext(acme,
                new User("u-bob", "acme", "Bob", Set.of(User.ROLE_SUPERVISOR)), null);
        carolCtx = new RequestContext(globex,
                new User("u-carol", "globex", "Carol", Set.of(User.ROLE_CSR)), null);
    }

    // ============ Attribution Completion ============

    @Test
    @DisplayName("governance-chain events get attributed: byUser finds alice's events")
    void eventsGetAttributed() {
        AuditLogger aliceLogger = trail.forRequest(aliceCtx, "support-bot");
        aliceLogger.log(AuditEvent.executed("run-1", QUERY_CALL, "{\"order\":8842}", 120));

        assertEquals(1, trail.byUser("u-alice").size());
        EnterpriseAuditEvent event = trail.byUser("u-alice").get(0);
        assertEquals("acme", event.tenantId());
        assertEquals("u-alice", event.userId());
        assertEquals("support-bot", event.agentName());
        assertEquals("query_order", event.toolName());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, event.status());
    }

    @Test
    @DisplayName("denials are attributed too - which user was blocked is a security signal")
    void denialsAttributed() {
        AuditLogger aliceLogger = trail.forRequest(aliceCtx, "support-bot");
        aliceLogger.log(AuditEvent.denied("run-2", REFUND_CALL, "CSR role cannot refund"));

        assertEquals(1, trail.byUser("u-alice").size());
        assertEquals(AuditEvent.AuditStatus.DENIED, trail.byUser("u-alice").get(0).status());
        assertEquals(0, trail.byUser("u-bob").size());
    }

    // ============ Cross-Request / Cross-Tenant Cuts ============

    @Test
    @DisplayName("byTenant separates tenants; byUser separates users within a tenant")
    void tenantAndUserCuts() {
        AuditLogger aliceLogger = trail.forRequest(aliceCtx, "support-bot");
        AuditLogger bobLogger = trail.forRequest(bobCtx, "support-bot");
        AuditLogger carolLogger = trail.forRequest(carolCtx, "globex-bot");

        aliceLogger.log(AuditEvent.executed("r1", QUERY_CALL, "ok", 10));
        bobLogger.log(AuditEvent.approved("r2", REFUND_CALL));
        carolLogger.log(AuditEvent.denied("r3", QUERY_CALL, "no access"));

        assertEquals(2, trail.byTenant("acme").size());
        assertEquals(1, trail.byTenant("globex").size());
        assertEquals(1, trail.byUser("u-alice").size());
        assertEquals(1, trail.byUser("u-bob").size());
        assertEquals(1, trail.byUser("u-carol").size());
        assertEquals(3, trail.size());
    }

    @Test
    @DisplayName("byTool answers the incident question: who called refund_order")
    void byToolCut() {
        trail.forRequest(aliceCtx, "support-bot")
                .log(AuditEvent.denied("r1", REFUND_CALL, "no role"));
        trail.forRequest(bobCtx, "support-bot")
                .log(AuditEvent.approved("r2", REFUND_CALL));
        trail.forRequest(bobCtx, "support-bot")
                .log(AuditEvent.executed("r2", REFUND_CALL, "refunded", 300));
        trail.forRequest(bobCtx, "support-bot")
                .log(AuditEvent.executed("r2", QUERY_CALL, "ok", 10));

        assertEquals(3, trail.byTool("refund_order").size());
        assertEquals(1, trail.byTool("query_order").size());
        // the incident cut preserves attribution
        assertTrue(trail.byTool("refund_order").stream()
                .anyMatch(e -> e.userId().equals("u-alice") && e.status() == AuditEvent.AuditStatus.DENIED));
    }

    // ============ Request-Scoped View Isolation ============

    @Test
    @DisplayName("the request-scoped logger sees only its own events (AuditLogger contract)")
    void requestViewIsolation() {
        AuditLogger aliceLogger = trail.forRequest(aliceCtx, "support-bot");
        AuditLogger bobLogger = trail.forRequest(bobCtx, "support-bot");

        aliceLogger.log(AuditEvent.executed("run-a", QUERY_CALL, "ok", 10));
        aliceLogger.log(AuditEvent.denied("run-a", REFUND_CALL, "denied"));
        bobLogger.log(AuditEvent.executed("run-b", QUERY_CALL, "ok", 20));

        assertEquals(2, aliceLogger.getAll().size());
        assertEquals(1, bobLogger.getAll().size());
        assertEquals(2, aliceLogger.getByRun("run-a").size(),
                "both of alice's events belong to run-a");
        assertEquals(0, aliceLogger.getByRun("run-b").size(),
                "alice's view must not see bob's run");
        assertEquals(1, aliceLogger.getByTool("refund_order").size());
        // but the shared ledger sees everything
        assertEquals(3, trail.size());
    }

    // ============ Validation ============

    @Test
    @DisplayName("forRequest rejects null context and blank agent name")
    void forRequestValidation() {
        assertThrows(NullPointerException.class, () -> trail.forRequest(null, "bot"));
        assertThrows(IllegalArgumentException.class, () -> trail.forRequest(aliceCtx, " "));
    }

    @Test
    @DisplayName("EnterpriseAuditEvent rejects missing attribution")
    void eventValidation() {
        AuditEvent raw = AuditEvent.executed("r", QUERY_CALL, "ok", 1);
        assertThrows(NullPointerException.class,
                () -> new EnterpriseAuditEvent(null, "acme", "u-a", "bot"));
        assertThrows(IllegalArgumentException.class,
                () -> new EnterpriseAuditEvent(raw, " ", "u-a", "bot"));
        assertThrows(IllegalArgumentException.class,
                () -> new EnterpriseAuditEvent(raw, "acme", "", "bot"));
        assertThrows(NullPointerException.class,
                () -> new EnterpriseAuditEvent(raw, "acme", "u-a", null));
        assertThrows(IllegalArgumentException.class,
                () -> new EnterpriseAuditEvent(raw, "acme", "u-a", ""));
    }
}

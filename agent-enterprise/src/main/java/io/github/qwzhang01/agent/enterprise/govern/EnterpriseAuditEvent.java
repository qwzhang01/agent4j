package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.security.AuditEvent;

import java.util.Objects;

/**
 * An audit fact with enterprise attribution (Stage 15 M15.3, D4
 * "compose, don't modify").
 * <p>
 * {@link AuditEvent} is the Stage 9 generic governance fact (runId + toolName
 * + status) - it knows WHAT happened, not WHO it belongs to. Enterprise
 * questions ("what did this employee make the Agent do?", "show me every
 * denial in the acme tenant this month") need attribution, which is an
 * enterprise-layer concept. Rather than changing the immutable Stage 9 record
 * (and every existing consumer), the enterprise layer wraps it:
 * composition over modification, the same discipline as the Stage 11/12/14
 * decorators.
 * <p>
 * Dual attribution model (D4): the full form is "executed by svc:{accountId}
 * on behalf of user:{id}". v1 records the user side (onBehalfOf) plus the
 * assembled agent name; the service-identity side is wired when the Stage 12
 * IdentityResolver joins (v2) - the field slot exists, the schema will not
 * change.
 *
 * @param event     the underlying governance fact (never null)
 * @param tenantId  which tenant this event belongs to
 * @param userId    on whose behalf the Agent acted ("u-alice")
 * @param agentName the executing agent's assembly name (v1: plain name;
 *                  v2: "svc:{accountId}" service identity)
 */
public record EnterpriseAuditEvent(
        AuditEvent event,
        String tenantId,
        String userId,
        String agentName
) {

    public EnterpriseAuditEvent {
        Objects.requireNonNull(event, "event must not be null");
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        requireText(agentName, "agentName");
    }

    // ============ Convenience Delegation ============

    /**
     * The tool that was called (delegates to the wrapped fact).
     */
    public String toolName() {
        return event.toolName();
    }

    /**
     * The outcome status (delegates to the wrapped fact).
     */
    public AuditEvent.AuditStatus status() {
        return event.status();
    }

    /**
     * The run that triggered the call (delegates to the wrapped fact).
     */
    public String runId() {
        return event.runId();
    }

    // ============ Helpers ============

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

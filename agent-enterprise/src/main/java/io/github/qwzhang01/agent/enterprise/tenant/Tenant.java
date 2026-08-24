package io.github.qwzhang01.agent.enterprise.tenant;

import java.util.Objects;

/**
 * A tenant entity - the top-level isolation boundary of the Enterprise Agent
 * Profile (Stage 15 M15.1).
 * <p>
 * Tenants answer "whose data is this": knowledge entries, user preferences,
 * audit trails and cost accounting are all partitioned by tenant. The isolation
 * mechanism itself is the Stage 8 scope whitelist ({@code tenant:{id}} scopes);
 * this record is the domain entity the registry keys on.
 * <p>
 * v1 honest boundary: {@code monthlyTokenBudget} is declared here and enforced
 * by the CostLedger (M15.3). Full cost governance (time windows, model routing,
 * dashboards) is Stage 18 scope.
 *
 * @param tenantId           unique tenant identifier (e.g. "acme")
 * @param displayName        human-readable name (e.g. "Acme Corp")
 * @param status             ACTIVE or SUSPENDED - suspended tenants fail closed
 * @param monthlyTokenBudget token budget per month; negative = unlimited
 *                           (convention shared with Stage 12 ServiceAccount)
 */
public record Tenant(
        String tenantId,
        String displayName,
        TenantStatus status,
        long monthlyTokenBudget
) {

    /** Unlimited budget sentinel (same convention as Stage 12 ServiceAccount). */
    public static final long UNLIMITED_BUDGET = -1L;

    /**
     * Lifecycle status of a tenant. Suspended tenants reject both registration
     * of new users and logins (fail-closed).
     */
    public enum TenantStatus {
        ACTIVE,
        SUSPENDED
    }

    public Tenant {
        requireText(tenantId, "tenantId");
        requireText(displayName, "displayName");
        Objects.requireNonNull(status, "status must not be null");
    }

    // ============ Factory Methods ============

    /**
     * An active tenant with unlimited budget.
     */
    public static Tenant active(String tenantId, String displayName) {
        return new Tenant(tenantId, displayName, TenantStatus.ACTIVE, UNLIMITED_BUDGET);
    }

    // ============ Accessors ============

    /**
     * Whether the tenant is currently active (registration and login allowed).
     */
    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }

    /**
     * Whether this tenant has a finite token budget.
     */
    public boolean hasBudget() {
        return monthlyTokenBudget >= 0;
    }

    /**
     * Derive a suspended copy (status transition returns a new instance).
     */
    public Tenant suspended() {
        return new Tenant(tenantId, displayName, TenantStatus.SUSPENDED, monthlyTokenBudget);
    }

    // ============ Helpers ============

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}

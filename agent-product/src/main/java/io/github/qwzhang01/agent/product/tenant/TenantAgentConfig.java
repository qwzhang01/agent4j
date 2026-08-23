package io.github.qwzhang01.agent.product.tenant;

import java.util.Objects;
import java.util.Set;

/**
 * Per-tenant configuration overlay (Stage 13 M13.5, D7): what a tenant is
 * allowed to tune on top of a definition.
 * <p>
 * v1 scope = CONFIG isolation, not RUNTIME isolation: tenant separation of
 * memory comes from the existing MemoryScope namespace pattern and identity
 * from ServiceAccount wiring (Stage 8/12); token quotas are Stage 18.
 * <p>
 * Overlay precedence at bind time:
 * <ul>
 *   <li>prompt: operator override (PromptManager.setTenantChannel) &gt;
 *       tenant's declared channel &gt; definition's declared channel &gt; stable</li>
 *   <li>model: tenant's model replaces the definition's primary provider
 *       (fallback chain stays); the model must be registered</li>
 *   <li>tools: disabledTools removes entries from the definition's tool
 *       subset (never adds - a tenant can restrict, never expand)</li>
 * </ul>
 *
 * @param tenantId       tenant identifier (matches AgentDefinition.metadata.tenant)
 * @param promptChannel  optional prompt channel override (stable/canary)
 * @param model          optional primary model override (registered name)
 * @param disabledTools  tool names to drop; empty = no restriction
 * @param serviceAccount optional service account id for identity wiring
 *                       (recorded in v1; enforcement is Stage 12 assembly)
 */
public record TenantAgentConfig(
        String tenantId,
        String promptChannel,
        String model,
        Set<String> disabledTools,
        String serviceAccount) {

    public TenantAgentConfig {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (promptChannel != null && !"stable".equals(promptChannel) && !"canary".equals(promptChannel)) {
            throw new IllegalArgumentException(
                    "promptChannel must be 'stable' or 'canary', got: " + promptChannel);
        }
        disabledTools = disabledTools == null ? Set.of() : Set.copyOf(disabledTools);
    }

    /**
     * Minimal config: tenant id only, everything else inherits.
     */
    public static TenantAgentConfig forTenant(String tenantId) {
        return new TenantAgentConfig(tenantId, null, null, null, null);
    }
}

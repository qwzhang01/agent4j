package io.github.qwzhang01.agent.channel.identity;

import java.time.Instant;
import java.util.Objects;

/**
 * A service account: the credential-and-configuration holder for an
 * {@link AgentIdentity} (Stage 12 D4).
 * <p>
 * One agent identity maps to exactly one active service account. The
 * account is what an admin provisions: it carries the granted
 * {@link IdentityScope}, an optional validity window, and a token budget
 * placeholder (reserved for Stage 18 cost governance, not enforced in v1).
 * <p>
 * The identity layer deliberately does NOT depend on agent-security:
 * there is no token, no secret here. Bridging to Stage 9's
 * PermissionChecker / AuditLogger happens at the assembly layer.
 *
 * @param accountId          account identifier, e.g. "svc-eng-bot-01" (distinct from agentId)
 * @param identity           the agent identity this account belongs to
 * @param grantedScope       the explicitly granted minimal resource scope
 * @param monthlyTokenBudget monthly token budget, {@link #UNLIMITED_BUDGET} = unlimited
 *                           (reserved for Stage 18; not enforced in v1)
 * @param validFrom          validity start, null = immediately valid
 * @param validUntil         validity end (exclusive), null = never expires
 */
public record ServiceAccount(
        String accountId,
        AgentIdentity identity,
        IdentityScope grantedScope,
        long monthlyTokenBudget,
        Instant validFrom,
        Instant validUntil
) {

    /** Sentinel for "no budget cap" (Stage 18 will wire real enforcement). */
    public static final long UNLIMITED_BUDGET = -1L;

    public ServiceAccount {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(identity, "identity must not be null");
        Objects.requireNonNull(grantedScope, "grantedScope must not be null");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (validFrom != null && validUntil != null && !validFrom.isBefore(validUntil)) {
            throw new IllegalArgumentException(
                    "validFrom must be before validUntil, got: " + validFrom + " .. " + validUntil);
        }
    }

    // ============ Factory Methods ============

    /**
     * An account with no validity window and unlimited budget placeholder.
     */
    public static ServiceAccount of(String accountId, AgentIdentity identity, IdentityScope grantedScope) {
        return new ServiceAccount(accountId, identity, grantedScope, UNLIMITED_BUDGET, null, null);
    }

    // ============ Predicates ============

    /**
     * Whether the account is valid at the given instant (validity window check).
     */
    public boolean isValidAt(Instant now) {
        Objects.requireNonNull(now, "now must not be null");
        if (validFrom != null && now.isBefore(validFrom)) {
            return false;
        }
        return validUntil == null || now.isBefore(validUntil);
    }

    /**
     * Whether a token budget is configured (vs. the unlimited placeholder).
     */
    public boolean hasBudgetCap() {
        return monthlyTokenBudget != UNLIMITED_BUDGET;
    }
}

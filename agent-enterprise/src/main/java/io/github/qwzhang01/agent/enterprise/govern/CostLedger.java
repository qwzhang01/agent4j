package io.github.qwzhang01.agent.enterprise.govern;

import io.github.qwzhang01.agent.enterprise.tenant.RequestContext;
import io.github.qwzhang01.agent.enterprise.tenant.Tenant;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Two-level cost ledger: pre-gate + post-recording (Stage 15 M15.3, D8).
 * <p>
 * Philosophy aligned with the Stage 7 {@code TokenBudget} (fail-closed
 * counter), different dimension: TokenBudget guards a single Run, this
 * ledger accumulates across runs per TENANT and per USER - the dimensions
 * enterprise accounting actually bills in. Time-window quotas, model routing
 * and degradation remain Stage 18 scope (TokenBudget's javadoc reserves
 * exactly that).
 * <p>
 * Two operations, strictly ordered:
 * <ul>
 *   <li>{@link #requireBudget} - the pre-gate at the request entry
 *       (ask/submitTask). Exhausted quota -> {@link BudgetExceededException};
 *       the request never starts, which is the cheapest possible failure</li>
 *   <li>{@link #record} - post-recording after the run finished, adding the
 *       run's token usage to both dimensions. v1 granularity is the REQUEST
 *       (no mid-run circuit breaker: the model call already went out,
 *       aborting midway saves half the cost and leaves a half-done state -
 *       see blueprint D8 honest boundary)</li>
 * </ul>
 * Quota sources: the tenant limit is read from the {@link Tenant} entity the
 * request carries (login-time snapshot - quota changes take effect on the
 * next login, not mid-flight); user limits are injected at assembly time
 * ({@code -1} = unlimited, the Stage 12/15 shared convention).
 */
public final class CostLedger {

    private final Map<String, Long> userLimits;
    private final Map<String, AtomicLong> tenantCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> userCounters = new ConcurrentHashMap<>();

    /**
     * @param userLimits userId -> token limit ({@code <=} 0 means unlimited);
     *                   users absent from the map are unlimited
     */
    public CostLedger(Map<String, Long> userLimits) {
        this.userLimits = userLimits == null ? Map.of() : Map.copyOf(userLimits);
    }

    /**
     * A ledger with no user limits (tenant budgets only).
     */
    public static CostLedger tenantOnly() {
        return new CostLedger(Map.of());
    }

    // ============ Pre-Gate ============

    /**
     * The request-entry budget gate. Fail-closed: exhausted tenant OR user
     * quota throws {@link BudgetExceededException} carrying dimension, used
     * and limit as evidence. Unlimited dimensions never throw.
     */
    public void requireBudget(RequestContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");

        Tenant tenant = ctx.tenant();
        if (tenant.hasBudget()) {
            long used = counter(tenantCounters, ctx.tenantId()).get();
            long limit = tenant.monthlyTokenBudget();
            if (used >= limit) {
                throw new BudgetExceededException(
                        "Tenant budget exhausted: tenant=" + ctx.tenantId()
                                + " used=" + used + " limit=" + limit,
                        BudgetExceededException.Dimension.TENANT, used, limit);
            }
        }

        Long userLimit = userLimits.get(ctx.userId());
        if (userLimit != null && userLimit > 0) {
            long used = counter(userCounters, ctx.userId()).get();
            if (used >= userLimit) {
                throw new BudgetExceededException(
                        "User budget exhausted: user=" + ctx.userId()
                                + " used=" + used + " limit=" + userLimit,
                        BudgetExceededException.Dimension.USER, used, userLimit);
            }
        }
    }

    // ============ Post-Recording ============

    /**
     * Record a finished request's token usage into both dimensions.
     *
     * @param ctx             the request context (attribution)
     * @param promptTokens    prompt tokens consumed by the run
     * @param completionTokens completion tokens consumed by the run
     */
    public void record(RequestContext ctx, long promptTokens, long completionTokens) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        long total = promptTokens + completionTokens;
        counter(tenantCounters, ctx.tenantId()).addAndGet(total);
        counter(userCounters, ctx.userId()).addAndGet(total);
    }

    // ============ Queries ============

    /**
     * Total tokens recorded for a tenant (the bill).
     */
    public long tenantUsed(String tenantId) {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return counter(tenantCounters, tenantId).get();
    }

    /**
     * Total tokens recorded for a user (the bill).
     */
    public long userUsed(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        return counter(userCounters, userId).get();
    }

    // ============ Helpers ============

    private static AtomicLong counter(Map<String, AtomicLong> map, String key) {
        return map.computeIfAbsent(key, k -> new AtomicLong());
    }
}

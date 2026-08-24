package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelRequest;

/**
 * Routing strategy interface (Stage 18 D6): WHO to call is a pluggable
 * decision, not a framework structure.
 * <p>
 * {@code RoutingModelClient} does exactly one thing - ask the router before
 * every call, then forward to the chosen candidate. Which model wins is this
 * interface's business: v1 ships {@link BudgetAwareRouter} (economics); v2 can
 * add TaskComplexityRouter (task markers), LatencyAwareRouter (SLA),
 * AvailabilityRouter (health) - routing policies are configuration, not code
 * structure.
 * <p>
 * Routing vs Fallback (Stage 1) - the two layers complement, neither replaces:
 * Fallback manages AVAILABILITY (switch after failure, passive, after the
 * fact); routing manages ECONOMICS (choose before the call, active, on the
 * happy path). Composition: {@code Routing(Fallback(premium, cheap))} - the
 * outer layer picks by budget, the inner layer catches crashes (even cheap
 * models die).
 * <p>
 * {@link BudgetSnapshot} is numbers, not identities (D5): the router never
 * sees a {@code BudgetBook}, a {@code ServiceAccount} or any domain object -
 * assembly translates ledgers into this record, keeping implementations
 * testable with plain data.
 */
public interface ModelRouter {

    /**
     * Decide which model should serve this call.
     *
     * @param request the model request about to be sent (may inform
     *                complexity-based routing)
     * @param budget  remaining-budget snapshot at decision time; the SAME
     *                router may see different snapshots call to call as the
     *                ledger drains
     * @return decision with a non-blank {@code reason} (enforced by
     *         {@link RouteDecision}); "why did this call go cheap" is part of
     *         the cost audit trail
     */
    RouteDecision route(ModelRequest request, BudgetSnapshot budget);

    /**
     * Remaining-budget numbers at decision time - a pure data view the
     * assembly layer derives from {@code BudgetBook.remainingOf/limitOf}
     * (D5: numbers injected, never the ledger itself).
     *
     * @param remainingTokens tokens still spendable under the routed budget
     * @param limitTokens     the configured limit; {@code -1} marks the
     *                        unlimited snapshot (no cap -> never downgrade)
     */
    record BudgetSnapshot(long remainingTokens, long limitTokens) {

        /** The no-cap view: routing never downgrades on an unlimited budget. */
        public static BudgetSnapshot unlimited() {
            return new BudgetSnapshot(Long.MAX_VALUE, -1L);
        }

        /**
         * The capped view; validates the invariants (limit &gt; 0,
         * 0 &lt;= remaining &lt;= limit) fail-fast at snapshot time - a router
         * must never see an impossible ledger.
         */
        public static BudgetSnapshot of(long remainingTokens, long limitTokens) {
            if (limitTokens <= 0) {
                throw new IllegalArgumentException("limitTokens must be positive: " + limitTokens);
            }
            if (remainingTokens < 0 || remainingTokens > limitTokens) {
                throw new IllegalArgumentException(
                        "remainingTokens must be within [0, " + limitTokens + "]: " + remainingTokens);
            }
            return new BudgetSnapshot(remainingTokens, limitTokens);
        }

        /** Whether this snapshot describes an uncapped budget. */
        public boolean isUnlimited() {
            return limitTokens < 0;
        }

        /** Remaining share of the limit, floored to an int percent (100 when unlimited). */
        public int remainingPercent() {
            return isUnlimited() ? 100 : (int) (remainingTokens * 100 / limitTokens);
        }
    }
}

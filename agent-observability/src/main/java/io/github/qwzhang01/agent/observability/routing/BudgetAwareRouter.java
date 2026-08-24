package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;

import java.util.Objects;

/**
 * The default routing strategy (Stage 18 D6): spend the good model while the
 * budget is healthy, downgrade when it is not, refuse when it is gone - the
 * first crossing of economics and availability.
 * <p>
 * Three bands, one per budget state (D3's three stages seen from the router):
 * <ul>
 *   <li>healthy (remaining % &gt;= threshold) -&gt; premium,
 *       reason "budget healthy: remaining N%"</li>
 *   <li>constrained (0 &lt; remaining % &lt; threshold) -&gt; cheap,
 *       reason "remaining N% &lt; T% threshold" - downgrading is not denial of
 *       service, it is lowering the cost density of what service remains</li>
 *   <li>exhausted (remaining == 0) -&gt; {@link BudgetExhaustedException}:
 *       cheap cannot help when the budget is GONE - any call would overdraft,
 *       so the honest move is the refusal (fail-closed, same discipline as
 *       {@code BudgetBook.requireBudget} DENIED)</li>
 * </ul>
 * <p>
 * Landing exactly ON the threshold stays premium (strict {@code <} compares) -
 * the same "denial is for overdraft, not for landing on the line" convention
 * as {@code BudgetBook}. Unlimited snapshots never downgrade (nothing to
 * conserve). The request itself is ignored in v1 - budget is the only signal;
 * complexity-aware routing is a v2 strategy, not a parameter on this one.
 */
public final class BudgetAwareRouter implements ModelRouter {

    private final String premiumModel;
    private final String cheapModel;
    private final int downgradeBelowPercent;

    /**
     * @param premiumModel          candidate key for the expensive/primary tier
     * @param cheapModel            candidate key for the cheap/downgrade tier
     * @param downgradeBelowPercent switch to the cheap tier when the remaining
     *                              percent drops strictly below this (1-99,
     *                              blueprint default 25)
     */
    public BudgetAwareRouter(String premiumModel, String cheapModel, int downgradeBelowPercent) {
        if (premiumModel == null || premiumModel.isBlank()) {
            throw new IllegalArgumentException("premiumModel must not be null or blank");
        }
        if (cheapModel == null || cheapModel.isBlank()) {
            throw new IllegalArgumentException("cheapModel must not be null or blank");
        }
        if (downgradeBelowPercent < 1 || downgradeBelowPercent > 99) {
            throw new IllegalArgumentException(
                    "downgradeBelowPercent must be within 1-99: " + downgradeBelowPercent);
        }
        this.premiumModel = premiumModel;
        this.cheapModel = cheapModel;
        this.downgradeBelowPercent = downgradeBelowPercent;
    }

    @Override
    public RouteDecision route(ModelRequest request, BudgetSnapshot budget) {
        Objects.requireNonNull(budget, "budget");
        if (budget.isUnlimited()) {
            return new RouteDecision(premiumModel,
                    "budget unlimited (no cap configured) - staying on " + premiumModel);
        }
        if (budget.remainingTokens() == 0) {
            throw new BudgetExhaustedException(
                    "budget exhausted: remaining 0 of " + budget.limitTokens()
                            + " tokens - refusing to route (any call would overdraft)",
                    0, budget.limitTokens());
        }
        int percent = budget.remainingPercent();
        if (percent < downgradeBelowPercent) {
            return new RouteDecision(cheapModel,
                    "remaining " + percent + "% < " + downgradeBelowPercent
                            + "% threshold - downgrading to " + cheapModel);
        }
        return new RouteDecision(premiumModel,
                "budget healthy: remaining " + percent + "% - staying on " + premiumModel);
    }
}

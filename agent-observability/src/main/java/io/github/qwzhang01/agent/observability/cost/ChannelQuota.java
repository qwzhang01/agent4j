package io.github.qwzhang01.agent.observability.cost;

/**
 * Pure number container for a channel-level token budget (Stage 18 D5).
 * <p>
 * The budget NUMBER is injected, not the identity: the assembly layer reads
 * {@code ServiceAccount.hasBudgetCap()} / {@code monthlyTokenBudget()} from the
 * channel module and constructs this record - this module does not import
 * agent-channel (same discipline as Stage 15 RequestContext explicit passing
 * and Stage 16 executorFactory injection: modules pass numbers, assemblies
 * pass semantics).
 * <p>
 * This cashes in the placeholder Stage 12 left open: {@code monthlyTokenBudget}
 * with {@code UNLIMITED_BUDGET = -1} was documented "留 Stage 18" - the
 * assembly pattern is:
 * <pre>
 * if (svc.hasBudgetCap()) {
 *     book.budget(BudgetDimension.CHANNEL, ch, svc.monthlyTokenBudget());
 * }
 * </pre>
 *
 * @param channelId         channel identifier (budget key)
 * @param monthlyTokenBudget monthly token cap, positive (no cap = do not construct)
 */
public record ChannelQuota(String channelId, long monthlyTokenBudget) {

    public ChannelQuota {
        if (channelId == null || channelId.isBlank()) {
            throw new IllegalArgumentException("channelId must not be null or blank");
        }
        if (monthlyTokenBudget <= 0) {
            throw new IllegalArgumentException(
                    "monthlyTokenBudget must be positive (no cap = do not construct a quota)");
        }
    }
}

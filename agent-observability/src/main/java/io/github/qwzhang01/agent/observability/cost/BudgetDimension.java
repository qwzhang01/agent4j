package io.github.qwzhang01.agent.observability.cost;

/**
 * The five budget escape surfaces (D4) - five gates, one per way money leaks.
 * <p>
 * One-dimensional budgets (per-user only) are not enough:
 * <ul>
 *   <li>{@link #RUN} - single-run circuit breaker: a fix-loop that never converges
 *       ([LIMIT] is the behavioral gate; this is the economic one - same
 *       trench, second sentry)</li>
 *   <li>{@link #USER} - monthly personal quota (CostLedger semantics)</li>
 *   <li>{@link #TENANT} - tenant-level total: one customer must not drain platform
 *       resources (already had this dimension in the enterprise domain)</li>
 *   <li>{@link #CHANNEL} - channel-level quota for shared agents (
 *       {@code ServiceAccount.monthlyTokenBudget} placeholder finally cashed in:
 *       50 members sharing one agent - "my own quota isn't used up" stops being a
 *       valid excuse when the ledger is kept per channel)</li>
 *   <li>{@link #AGENT} - per service-identity quota: a sales agent and an
 *       engineering agent burn different budgets (the economic face of
 *       identity isolation)</li>
 * </ul>
 */
public enum BudgetDimension {
    RUN,
    USER,
    TENANT,
    CHANNEL,
    AGENT
}

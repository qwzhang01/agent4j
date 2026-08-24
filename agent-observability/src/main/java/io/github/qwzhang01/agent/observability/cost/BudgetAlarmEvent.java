package io.github.qwzhang01.agent.observability.cost;

import io.github.qwzhang01.agent.observability.metrics.MetricsSink;

/**
 * Budget warning event flowing to {@link MetricsSink#onAlarm} (WARN level,
 * non-blocking) - the "be seen" half of the D3 warning/blocking separation.
 *
 * @param dimension   which budget surface fired the warning
 * @param key         budget key (userId / channelId / tenantId / runId / agentId)
 * @param usedTokens  tokens already recorded against this budget
 * @param limitTokens the configured limit
 * @param percentUsed percent of the limit already used (int 0-100)
 */
public record BudgetAlarmEvent(
        BudgetDimension dimension,
        String key,
        long usedTokens,
        long limitTokens,
        int percentUsed) {
}

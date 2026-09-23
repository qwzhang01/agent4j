package io.github.qwzhang01.agent.core.run;

/**
 * Run-scoped budget view (placeholder for Stage 2/7 wiring).
 * <p>
 * v1: immutable limits carried in {@link RunContext}; consumption
 * accounting stays in agent-scheduler's TokenBudget until the Model and
 * Tool boundaries automatically consume from this view .
 * Nulls mean "no limit configured for this axis".
 */
public record RunBudget(
        Long maxTotalTokens,
        Long maxCostMicros,
        Integer maxToolCalls
) {

    public static RunBudget none() {
        return new RunBudget(null, null, null);
    }
}

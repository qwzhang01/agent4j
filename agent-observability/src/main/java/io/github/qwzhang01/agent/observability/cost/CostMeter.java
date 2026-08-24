package io.github.qwzhang01.agent.observability.cost;

import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;

import java.util.Objects;

/**
 * TokenUsage -&gt; microUSD converter (Stage 18 M18.2).
 * <p>
 * Integer arithmetic only, round-half-up: the accounting path never touches
 * floating point. Overflow honesty: tokens are int (&lt; 2^31) and realistic
 * prices stay far below 4e9 microUSD/M, so {@code tokens * price} fits a long
 * with huge headroom (limit ~4.3e9 microUSD/M, i.e. $4,300 per million tokens).
 * <p>
 * Fail-loud contract (blueprint D3 / F6): a model without a pricing row throws
 * {@link IllegalArgumentException} - a missing price is a configuration bug, and
 * a fake zero-cost entry is worse than a loud failure. The one deliberate
 * exception is the {@link io.github.qwzhang01.agent.observability.metrics.MetricsCollector}
 * aggregation path, which treats unpriced calls as 0 with a warning: metrics are
 * a side channel and must not blow up the run they observe (two disciplines,
 * deliberately different - direct pricing questions stay honest, side-channel
 * aggregation stays harmless).
 */
public final class CostMeter {

    private static final long HALF_MILLION = 500_000L;

    private final PricingTable table;

    public CostMeter(PricingTable table) {
        this.table = Objects.requireNonNull(table, "table");
    }

    /** Cost of one call described by prompt/completion token counts, in microUSD. */
    public long costMicros(String model, long promptTokens, long completionTokens) {
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        PricingTable.Price price = table.priceOf(model);
        return scaled(promptTokens, price.inputMicrosPerMillion())
                + scaled(completionTokens, price.outputMicrosPerMillion());
    }

    /** Cost of one captured model call (usage-unreported calls have 0 tokens -&gt; 0 cost, honest). */
    public long costMicros(ModelCallMetrics call) {
        Objects.requireNonNull(call, "call");
        return costMicros(call.model(), call.promptTokens(), call.completionTokens());
    }

    /** (tokens * microsPerMillion) rounded-half-up to whole microUSD. */
    private static long scaled(long tokens, long microsPerMillion) {
        return (tokens * microsPerMillion + HALF_MILLION) / 1_000_000L;
    }
}

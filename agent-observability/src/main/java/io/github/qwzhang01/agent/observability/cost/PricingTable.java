package io.github.qwzhang01.agent.observability.cost;

/**
 * Model price table: input/output price per one million tokens, in integer
 * microUSD (1 microUSD = 1e-6 USD) - no floating point in the accounting path.
 * <p>
 * Example rows (real-world scale, for orientation):
 * <pre>
 *   gpt-4o     input $2.50/M  -> 2_500_000 microUSD/M
 *              output $10.00/M -> 10_000_000 microUSD/M
 *   gpt-4o-mini input $0.15/M  ->   150_000 microUSD/M
 * </pre>
 * <p>
 * The table is immutable after build; a missing row is a configuration bug and
 * {@link CostMeter} fails loud on it rather than guessing a zero cost
 * (blueprint D3: never fake an accounting entry).
 */
public final class PricingTable {

    private final java.util.Map<String, Price> prices;

    private PricingTable(java.util.Map<String, Price> prices) {
        this.prices = prices;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Whether a price row exists for the model (装配期校验用). */
    public boolean hasPricing(String model) {
        return model != null && prices.containsKey(model);
    }

    /** Package-private lookup; throws when missing (fail-loud contract lives in CostMeter). */
    Price priceOf(String model) {
        Price price = model == null ? null : prices.get(model);
        if (price == null) {
            throw new IllegalArgumentException("no pricing for model: " + model);
        }
        return price;
    }

    /**
     * One model's price row, integer microUSD per million tokens.
     * <p>
     * Stage 5.1: the two cache fields default to 0 = no cache accounting,
     * preserving the pre-Stage-5 two-field behaviour for every existing
     * table. Non-zero values engage the cache-aware prompt split in
     * {@link CostMeter#costMicros(String, long, long, long, long)}:
     * cached tokens billed at {@code cacheReadMicrosPerMillion}, cache-write
     * tokens at {@code cacheWriteMicrosPerMillion}, the uncached remainder
     * at {@code inputMicrosPerMillion} - the same disjoint-split semantics
     * as {@code routing.CachePricing.promptCostMicros} (E3 / decision 26).
     */
    public record Price(long inputMicrosPerMillion, long outputMicrosPerMillion,
                        long cacheReadMicrosPerMillion, long cacheWriteMicrosPerMillion) {

        public Price(long inputMicrosPerMillion, long outputMicrosPerMillion) {
            this(inputMicrosPerMillion, outputMicrosPerMillion, 0, 0);
        }
    }

    public static final class Builder {
        private final java.util.Map<String, Price> prices = new java.util.LinkedHashMap<>();

        /**
         * @param model                  model identifier as it appears in requests
         * @param inputMicrosPerMillion  prompt-token price, microUSD per 1M tokens (&gt; 0)
         * @param outputMicrosPerMillion completion-token price, microUSD per 1M tokens (&gt; 0)
         */
        public Builder price(String model, long inputMicrosPerMillion, long outputMicrosPerMillion) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be null or blank");
            }
            if (inputMicrosPerMillion <= 0 || outputMicrosPerMillion <= 0) {
                throw new IllegalArgumentException(
                        "prices must be positive microUSD per million tokens: " + model);
            }
            prices.put(model, new Price(inputMicrosPerMillion, outputMicrosPerMillion));
            return this;
        }

        /**
         * Stage 5.1: price row with cache accounting. Cache fields follow the
         * {@code routing.CachePricing} semantics - read discount, write premium.
         */
        public Builder price(String model, long inputMicrosPerMillion, long outputMicrosPerMillion,
                             long cacheReadMicrosPerMillion, long cacheWriteMicrosPerMillion) {
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be null or blank");
            }
            if (inputMicrosPerMillion <= 0 || outputMicrosPerMillion <= 0) {
                throw new IllegalArgumentException(
                        "prices must be positive microUSD per million tokens: " + model);
            }
            if (cacheReadMicrosPerMillion < 0 || cacheWriteMicrosPerMillion < 0) {
                throw new IllegalArgumentException("cache prices must be >= 0: " + model);
            }
            if (cacheReadMicrosPerMillion > inputMicrosPerMillion) {
                throw new IllegalArgumentException(
                        "cache read must not cost more than uncached input: " + model);
            }
            prices.put(model, new Price(inputMicrosPerMillion, outputMicrosPerMillion,
                    cacheReadMicrosPerMillion, cacheWriteMicrosPerMillion));
            return this;
        }

        public PricingTable build() {
            return new PricingTable(java.util.Map.copyOf(prices));
        }
    }
}

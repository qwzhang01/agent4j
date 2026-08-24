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

    /** One model's price row, integer microUSD per million tokens. */
    public record Price(long inputMicrosPerMillion, long outputMicrosPerMillion) {
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

        public PricingTable build() {
            return new PricingTable(java.util.Map.copyOf(prices));
        }
    }
}

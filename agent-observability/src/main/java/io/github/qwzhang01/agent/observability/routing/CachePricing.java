package io.github.qwzhang01.agent.observability.routing;

import java.util.Objects;

/**
 * Cache-aware pricing: one model's prompt cache economics (E3, decision 26).
 * <p>
 * Prompt caches are not free money - they have a WRITE premium and a READ
 * discount relative to the base input price. Every provider models this
 * differently but the shape is identical:
 * <pre>
 *   cost(prompt) = uncachedTokens * inputMicrosPerMillion
 *                + cachedTokens  * cacheReadMicrosPerMillion
 *                + cacheWriteTokens * cacheWriteMicrosPerMillion
 * </pre>
 * <p>
 * Calibrated to Anthropic's published multipliers (the industry's most
 * explicit cache pricing, orientation-scale numbers):
 * <ul>
 *   <li>read: 0.1x of base input (the discount that makes stable prefixes
 *       worth engineering for)</li>
 *   <li>write: 1.25x of base input (the premium a cache-breaking request
 *       pays on the tokens it re-inserts)</li>
 * </ul>
 * <p>
 * OpenAI-style implicit caching has NO write premium (writes are free, reads
 * are discounted) - model that with {@code cacheWriteMicrosPerMillion = 0}.
 * The harness prints both variants so the notes can show how the two
 * provider styles price the SAME prefix behavior.
 * <p>
 * All values are integer microUSD per one million tokens, same discipline as
 * {@code PricingTable}: no floating point in the accounting path.
 *
 * @param inputMicrosPerMillion      base prompt price (uncached tokens)
 * @param cacheReadMicrosPerMillion  price of a token served from cache
 * @param cacheWriteMicrosPerMillion price of a token written INTO the cache;
 *                                   0 for providers with free implicit writes
 */
public record CachePricing(
        long inputMicrosPerMillion,
        long cacheReadMicrosPerMillion,
        long cacheWriteMicrosPerMillion) {

    public CachePricing {
        if (inputMicrosPerMillion < 0 || cacheReadMicrosPerMillion < 0
                || cacheWriteMicrosPerMillion < 0) {
            throw new IllegalArgumentException("prices must be >= 0 microUSD per million tokens");
        }
        if (cacheReadMicrosPerMillion > inputMicrosPerMillion) {
            throw new IllegalArgumentException(
                    "cache read must not cost more than uncached input: "
                            + cacheReadMicrosPerMillion + " > " + inputMicrosPerMillion);
        }
    }

    /**
     * Anthropic-style explicit caching: read 0.1x, write 1.25x of base input.
     */
    public static CachePricing anthropic(long inputMicrosPerMillion) {
        return new CachePricing(
                inputMicrosPerMillion,
                Math.round(inputMicrosPerMillion * 0.1),
                Math.round(inputMicrosPerMillion * 1.25));
    }

    /**
     * OpenAI-style implicit caching: reads discounted, writes free.
     */
    public static CachePricing openAiStyle(long inputMicrosPerMillion) {
        return new CachePricing(
                inputMicrosPerMillion,
                Math.round(inputMicrosPerMillion * 0.5),
                0);
    }

    /**
     * The prompt-side cost of one call under this pricing.
     * <p>
     * Semantics (no double charging): the prompt splits into three disjoint
     * parts - cached (read discount), written (cache-insert premium that
     * REPLACES the base price, per Anthropic's published model) and base
     * (uncached, unwritten input at the full price).
     * <pre>
     *   prompt = cached + written + base
     *   cost   = cached*read + written*write + base*input
     * </pre>
     *
     * @param promptTokens  full billed prompt (cached + written + base)
     * @param cachedTokens  portion served from cache (read discount)
     * @param writtenTokens portion written INTO the cache this call (write
     *                      premium replaces base input for these tokens);
     *                      0 for providers with free implicit writes
     */
    public long promptCostMicros(int promptTokens, int cachedTokens, int writtenTokens) {
        Objects.requireNonNull(this, "pricing");
        if (promptTokens < 0 || cachedTokens < 0 || writtenTokens < 0) {
            throw new IllegalArgumentException("token counts must be >= 0");
        }
        if (cachedTokens > promptTokens) {
            throw new IllegalArgumentException("cachedTokens (" + cachedTokens
                    + ") must not exceed promptTokens (" + promptTokens + ")");
        }
        if (writtenTokens > promptTokens - cachedTokens) {
            throw new IllegalArgumentException("writtenTokens (" + writtenTokens
                    + ") must not exceed the uncached portion ("
                    + (promptTokens - cachedTokens) + ")");
        }
        long base = (long) promptTokens - cachedTokens - writtenTokens;
        return base * inputMicrosPerMillion / 1_000_000
                + (long) cachedTokens * cacheReadMicrosPerMillion / 1_000_000
                + (long) writtenTokens * cacheWriteMicrosPerMillion / 1_000_000;
    }

    /**
     * Effective per-prompt-token price when the whole prompt is cached vs
     * nothing cached - the two extremes of the hit-rate axis, for the notes.
     */
    public double readMultiplier() {
        return inputMicrosPerMillion == 0 ? 0 : (double) cacheReadMicrosPerMillion / inputMicrosPerMillion;
    }

    public double writeMultiplier() {
        return inputMicrosPerMillion == 0 ? 0 : (double) cacheWriteMicrosPerMillion / inputMicrosPerMillion;
    }
}

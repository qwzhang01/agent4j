package io.github.qwzhang01.agent.observability.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for E3's cache pricing: disjoint cost parts, multipliers,
 * validation, and the two provider styles.
 */
class CachePricingTest {

    @Test
    @DisplayName("anthropic style: read 0.1x, write 1.25x of base input")
    void anthropicMultipliers() {
        CachePricing p = CachePricing.anthropic(3_000_000);
        assertEquals(0.1, p.readMultiplier(), 1e-9);
        assertEquals(1.25, p.writeMultiplier(), 1e-9);
        assertEquals(300_000, p.cacheReadMicrosPerMillion());
        assertEquals(3_750_000, p.cacheWriteMicrosPerMillion());
    }

    @Test
    @DisplayName("openai style: reads discounted, writes free")
    void openAiMultipliers() {
        CachePricing p = CachePricing.openAiStyle(3_000_000);
        assertEquals(0.5, p.readMultiplier(), 1e-9);
        assertEquals(0.0, p.writeMultiplier(), 1e-9);
        assertEquals(0, p.cacheWriteMicrosPerMillion());
    }

    @Test
    @DisplayName("all-cached prompt: billed at the read discount only")
    void fullyCachedCost() {
        CachePricing p = CachePricing.anthropic(3_000_000);
        // 1000 cached, 0 written, 0 base -> 1000 * 300_000 / 1M = 300 micros
        assertEquals(300, p.promptCostMicros(1000, 1000, 0));
    }

    @Test
    @DisplayName("cold prompt with explicit write: base + write premium, no double charge")
    void coldWriteCost() {
        CachePricing p = CachePricing.anthropic(3_000_000);
        // 200 base + 800 written (base price replaced by 1.25x for written part)
        // = 200*3M/1M + 800*3.75M/1M = 600 + 3000 = 3600 micros
        assertEquals(3600, p.promptCostMicros(1000, 0, 800));
    }

    @Test
    @DisplayName("mixed prompt: cached + written + base disjoint parts")
    void mixedCost() {
        CachePricing p = CachePricing.anthropic(3_000_000);
        // 400 cached (0.1x) + 300 written (1.25x) + 300 base (1x)
        // = 400*0.3M + 300*3.75M + 300*3M / 1M = 120 + 1125 + 900 = 2145 micros
        assertEquals(2145, p.promptCostMicros(1000, 400, 300));
    }

    @Test
    @DisplayName("implicit style: written tokens cost nothing extra")
    void implicitWriteFree() {
        CachePricing p = CachePricing.openAiStyle(3_000_000);
        // 500 cached (0.5x) + 500 written (0x) = 500*1.5M/1M = 750 micros
        assertEquals(750, p.promptCostMicros(1000, 500, 500));
    }

    @Test
    @DisplayName("validation: read > input rejected, written > uncached rejected")
    void validation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CachePricing(1_000_000, 2_000_000, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CachePricing(1_000_000, 100_000, 0).promptCostMicros(1000, 100, 950));
        assertThrows(IllegalArgumentException.class,
                () -> new CachePricing(1_000_000, 100_000, 0).promptCostMicros(1000, 1100, 0));
    }
}

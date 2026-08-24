package io.github.qwzhang01.agent.observability.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PricingTableTest {

    @Test
    @DisplayName("hasPricing reflects configured rows only")
    void hasPricing() {
        PricingTable table = PricingTable.builder()
                .price("premium", 2_500_000L, 10_000_000L)
                .build();
        assertTrue(table.hasPricing("premium"));
        assertFalse(table.hasPricing("cheap"));
        assertFalse(table.hasPricing(null));
    }

    @Test
    @DisplayName("builder guards: blank model and non-positive prices rejected at assembly time")
    void builderGuards() {
        PricingTable.Builder b = PricingTable.builder();
        assertThrows(IllegalArgumentException.class, () -> b.price(" ", 1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> b.price("m", 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> b.price("m", 1L, -1L));
    }

    @Test
    @DisplayName("same model priced twice: last write wins (assembly-time override, no merge)")
    void lastWriteWins() {
        PricingTable table = PricingTable.builder()
                .price("m", 1_000_000L, 1_000_000L)
                .price("m", 2_000_000L, 2_000_000L)
                .build();
        // observable through the meter, the table's public read surface
        CostMeter meter = new CostMeter(table);
        // 1M tokens at 2M microUSD/M = 2,000,000 microUSD ($2) per side; input + output = 4,000,000
        assertEquals(4_000_000L, meter.costMicros("m", 1_000_000L, 1_000_000L));
    }
}

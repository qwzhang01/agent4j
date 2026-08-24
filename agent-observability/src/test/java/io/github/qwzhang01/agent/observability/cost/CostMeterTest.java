package io.github.qwzhang01.agent.observability.cost;

import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CostMeterTest {

    private static PricingTable table() {
        return PricingTable.builder()
                .price("premium", 2_500_000L, 10_000_000L)   // $2.5/M in, $10/M out
                .price("cheap", 150_000L, 600_000L)           // $0.15/M in, $0.60/M out
                .build();
    }

    @Test
    @DisplayName("exact conversion: 800 in + 200 out on premium = 2000 + 2000 microUSD")
    void exactConversion() {
        CostMeter meter = new CostMeter(table());
        // 800 * 2_500_000 / 1_000_000 = 2000; 200 * 10_000_000 / 1_000_000 = 2000
        assertEquals(4000L, meter.costMicros("premium", 800, 200));
    }

    @Test
    @DisplayName("input and output priced separately (cheap model sanity)")
    void separatePrices() {
        CostMeter meter = new CostMeter(table());
        // 1000 * 150_000 / 1M = 150; 500 * 600_000 / 1M = 300
        assertEquals(450L, meter.costMicros("cheap", 1000, 500));
    }

    @Test
    @DisplayName("round-half-up on sub-microUSD results: 1 token at 500_000/M = 0.5 -> 1")
    void roundHalfUp() {
        PricingTable half = PricingTable.builder().price("m", 500_000L, 500_000L).build();
        CostMeter meter = new CostMeter(half);
        assertEquals(1L, meter.costMicros("m", 1, 0), "0.5 microUSD rounds half-up to 1");
        assertEquals(0L, meter.costMicros("m", 0, 0), "zero tokens are exactly zero");
    }

    @Test
    @DisplayName("usage-unreported call (0 tokens) costs 0 with pricing present - honest zero")
    void zeroTokensZeroCost() {
        CostMeter meter = new CostMeter(table());
        assertEquals(0L, meter.costMicros("premium", 0, 0));
    }

    @Test
    @DisplayName("missing pricing row fails loud: IAE names the model (never fake a zero-cost entry)")
    void missingPricingFailsLoud() {
        CostMeter meter = new CostMeter(table());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> meter.costMicros("gpt-unknown", 100, 50));
        assertTrue(ex.getMessage().contains("gpt-unknown"));
    }

    @Test
    @DisplayName("ModelCallMetrics overload prices the captured boundary event directly")
    void metricsOverload() {
        CostMeter meter = new CostMeter(table());
        ModelCallMetrics call = new ModelCallMetrics("premium", 5L, 800, 200, 1000, "stop", null);
        assertEquals(4000L, meter.costMicros(call));
        // failure call: tokens are 0 -> cost 0 even though the call itself failed
        ModelCallMetrics failed = new ModelCallMetrics("premium", 5L, 0, 0, 0, null, "timeout");
        assertEquals(0L, meter.costMicros(failed));
    }

    @Test
    @DisplayName("negative token counts rejected (garbage in, IAE out)")
    void negativeRejected() {
        CostMeter meter = new CostMeter(table());
        assertThrows(IllegalArgumentException.class, () -> meter.costMicros("premium", -1, 0));
    }
}

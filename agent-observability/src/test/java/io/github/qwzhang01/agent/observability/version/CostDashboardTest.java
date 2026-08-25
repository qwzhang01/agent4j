package io.github.qwzhang01.agent.observability.version;

import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.CostMeter;
import io.github.qwzhang01.agent.observability.cost.PricingTable;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CostDashboardTest {

    // ============ booking ============

    @Test
    @DisplayName("record/costOf: amounts merge per key; each dimension keeps its own keys")
    void bookingMerges() {
        CostDashboard dashboard = new CostDashboard();
        dashboard.record(BudgetDimension.USER, "alice", 1_000)
                .record(BudgetDimension.USER, "alice", 500)
                .record(BudgetDimension.USER, "bob", 300)
                .record(BudgetDimension.TENANT, "acme", 1_800);

        assertEquals(1_500, dashboard.costOf(BudgetDimension.USER, "alice"));
        assertEquals(300, dashboard.costOf(BudgetDimension.USER, "bob"));
        assertEquals(1_800, dashboard.costOf(BudgetDimension.TENANT, "acme"));
        assertEquals(0, dashboard.costOf(BudgetDimension.USER, "nobody"));
    }

    @Test
    @DisplayName("reconciliation: total booked once, four angles - every dimension equals the total")
    void reconciliation() {
        CostDashboard dashboard = new CostDashboard();
        long[] events = {650, 1_500, 4_000};
        for (long cost : events) {
            dashboard.recordCost(cost);  // the real money, once
            dashboard.record(BudgetDimension.TENANT, "acme", cost);
            dashboard.record(BudgetDimension.CHANNEL, "eng", cost);
            dashboard.record(BudgetDimension.AGENT, "assist", cost);
            dashboard.record(BudgetDimension.USER, "alice", cost);
        }

        assertEquals(6_150, dashboard.totalCost());
        assertEquals(dashboard.totalCost(), dashboard.totalOf(BudgetDimension.TENANT));
        assertEquals(dashboard.totalCost(), dashboard.totalOf(BudgetDimension.CHANNEL));
        assertEquals(dashboard.totalCost(), dashboard.totalOf(BudgetDimension.AGENT));
        assertEquals(dashboard.totalCost(), dashboard.totalOf(BudgetDimension.USER));
    }

    @Test
    @DisplayName("totalCost never sums across dimensions: fan-out does not multiply the money")
    void totalIsNotTheSumOfAngles() {
        CostDashboard dashboard = new CostDashboard();
        dashboard.recordCost(1_000);
        dashboard.record(BudgetDimension.TENANT, "acme", 1_000);
        dashboard.record(BudgetDimension.USER, "alice", 1_000);

        assertEquals(1_000, dashboard.totalCost(), "two angles, one account, 1000 microUSD - not 2000");
    }

    @Test
    @DisplayName("guards: negative cost rejected, zero books nothing, blank key rejected")
    void bookingGuards() {
        CostDashboard dashboard = new CostDashboard();

        assertThrows(IllegalArgumentException.class,
                () -> dashboard.record(BudgetDimension.USER, "a", -1));
        assertThrows(IllegalArgumentException.class,
                () -> dashboard.record(BudgetDimension.USER, " ", 1));
        dashboard.record(BudgetDimension.USER, "a", 0);
        assertTrue(dashboard.keysOf(BudgetDimension.USER).isEmpty(), "zero does not materialize a key");
    }

    @Test
    @DisplayName("keysOf: insertion order (deterministic export rows)")
    void keysInInsertionOrder() {
        CostDashboard dashboard = new CostDashboard();
        dashboard.record(BudgetDimension.USER, "zoe", 1).record(BudgetDimension.USER, "amy", 2);

        assertEquals(java.util.List.of("zoe", "amy"), dashboard.keysOf(BudgetDimension.USER));
    }

    // ============ exports ============

    @Test
    @DisplayName("exportCsv: header + one row per key, insertion order")
    void csvExport() throws IOException {
        CostDashboard dashboard = new CostDashboard();
        dashboard.record(BudgetDimension.TENANT, "acme", 41_200).record(BudgetDimension.TENANT, "globex", 800);
        Path file = Files.createTempFile("dashboard", ".csv");

        dashboard.exportCsv(BudgetDimension.TENANT, file);

        assertEquals("key,cost_micros\nacme,41200\nglobex,800\n",
                Files.readString(file));
        Files.deleteIfExists(file);
    }

    @Test
    @DisplayName("exportJsonl: one {dimension,key,cost_micros} object per line")
    void jsonlExport() throws IOException {
        CostDashboard dashboard = new CostDashboard();
        dashboard.record(BudgetDimension.CHANNEL, "eng", 41_200);
        Path file = Files.createTempFile("dashboard", ".jsonl");

        dashboard.exportJsonl(BudgetDimension.CHANNEL, file);

        assertEquals("{\"dimension\":\"CHANNEL\",\"key\":\"eng\",\"cost_micros\":41200}\n",
                Files.readString(file));
        Files.deleteIfExists(file);
    }

    // ============ attribution sink wiring ============

    @Test
    @DisplayName("attributionSink: prices each model call, books all dimensions - one account, four angles")
    void attributionSinkBooks() {
        PricingTable table = PricingTable.builder()
                .price("premium", 2_500_000L, 10_000_000L)
                .build();
        CostDashboard.AttributionSink sink = CostDashboard.attributionSink(
                new CostMeter(table),
                Map.of(BudgetDimension.TENANT, "acme", BudgetDimension.USER, "alice"));

        sink.onModelCall(new ModelCallMetrics("premium", 1L, 100, 40, 140, "stop", null));  // 650

        CostDashboard dashboard = sink.dashboard();
        assertEquals(650, dashboard.costOf(BudgetDimension.TENANT, "acme"));
        assertEquals(650, dashboard.costOf(BudgetDimension.USER, "alice"));
        assertEquals(650, dashboard.totalCost());
    }

    @Test
    @DisplayName("attributionSink: unpriced model skipped with no throw (metrics are a side channel)")
    void attributionSinkUnpricedSkipped() {
        PricingTable table = PricingTable.builder().price("known", 1_000_000L, 1_000_000L).build();
        CostDashboard.AttributionSink sink = CostDashboard.attributionSink(
                new CostMeter(table), Map.of(BudgetDimension.USER, "alice"));

        assertDoesNotThrow(() ->
                sink.onModelCall(new ModelCallMetrics("mystery", 1L, 100, 40, 140, "stop", null)));
        assertEquals(0, sink.dashboard().totalCost(), "fail-loud is CostMeter's contract; the adapter side-channels");
    }

    @Test
    @DisplayName("attributionSink guards: empty attribution map rejected at assembly time")
    void attributionSinkGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> CostDashboard.attributionSink(new CostMeter(PricingTable.builder()
                        .price("m", 1L, 1L).build()), Map.of()));
    }
}

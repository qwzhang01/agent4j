package io.github.qwzhang01.agent.observability.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.CostMeter;
import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The cost dashboard's DATA outlet (Stage 18 D8/acceptance 9): aggregate
 * microUSD by dimension key, export per-dimension breakdowns - the
 * dashboard UI itself is a frontend concern and stays out of v1.
 * <p>
 * Four breakdown dimensions (tenant / channel / agent / user) share one
 * {@link BudgetDimension} vocabulary with {@code BudgetBook}. The TOTAL is a
 * first-class ledger separate from the breakdowns: one real cost event is
 * booked ONCE via {@link #recordCost} (and once per angle via
 * {@link #record}); summing breakdown rows across dimensions would
 * multiply-count multi-attributed events - the classic double-counting trap.
 * The reconciliation discipline is structural: when assembly attributes every
 * event to all four dimensions, each dimension's total MUST equal
 * {@link #totalCost()} - the same account read from four angles. The example
 * asserts this; a real deployment alerts on it.
 * <p>
 * {@link #attributionSink} is the one-line wiring for the common shape
 * "one process serves a fixed attribution context" (the demo tenant/user/
 * channel/agent are constants): it prices model calls via {@link CostMeter}
 * and books the microUSD to every configured dimension key - a side channel,
 * unpriced models are skipped with a warn, never thrown.
 */
public final class CostDashboard {

    private static final Logger log = LoggerFactory.getLogger(CostDashboard.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // dimension -> key -> accumulated microUSD, insertion order preserved (deterministic exports)
    private final Map<BudgetDimension, LinkedHashMap<String, Long>> breakdown = new LinkedHashMap<>();
    private long totalLedger;

    /**
     * Book ONE real cost event into the authoritative total - exactly once
     * per event, regardless of how many breakdown angles
     * {@link #record} fans it out to.
     */
    public synchronized CostDashboard recordCost(long costMicros) {
        if (costMicros < 0) {
            throw new IllegalArgumentException("costMicros must not be negative: " + costMicros);
        }
        totalLedger += costMicros;
        return this;
    }

    /** Book costMicros against one dimension key (negative amounts rejected). */
    public synchronized CostDashboard record(BudgetDimension dimension, String key, long costMicros) {
        Objects.requireNonNull(dimension, "dimension");
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be null or blank");
        }
        if (costMicros < 0) {
            throw new IllegalArgumentException("costMicros must not be negative: " + costMicros);
        }
        if (costMicros == 0) {
            return this;  // nothing to book, key not materialized (absence is honest)
        }
        breakdown.computeIfAbsent(dimension, d -> new LinkedHashMap<>())
                .merge(key, costMicros, Long::sum);
        return this;
    }

    /** Accumulated microUSD of one dimension key; 0 when nothing booked. */
    public synchronized long costOf(BudgetDimension dimension, String key) {
        return breakdown.getOrDefault(dimension, new LinkedHashMap<>()).getOrDefault(key, 0L);
    }

    /** Sum over all keys of one dimension - one angle of the total account. */
    public synchronized long totalOf(BudgetDimension dimension) {
        return breakdown.getOrDefault(dimension, new LinkedHashMap<>()).values().stream()
                .mapToLong(Long::longValue).sum();
    }

    /** The authoritative total: everything ever booked via {@link #recordCost} - never summed across dimensions. */
    public synchronized long totalCost() {
        return totalLedger;
    }

    /** Booked keys of one dimension, insertion order (export row order). */
    public synchronized java.util.List<String> keysOf(BudgetDimension dimension) {
        return java.util.List.copyOf(breakdown.getOrDefault(dimension, new LinkedHashMap<>()).keySet());
    }

    // ============ Exports ============

    /**
     * CSV export of one dimension: header {@code key,cost_micros} plus one
     * row per key, insertion order. Parent directories are created.
     */
    public synchronized void exportCsv(BudgetDimension dimension, Path file) throws IOException {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(file, "file");
        StringBuilder sb = new StringBuilder("key,cost_micros\n");
        for (Map.Entry<String, Long> e
                : breakdown.getOrDefault(dimension, new LinkedHashMap<>()).entrySet()) {
            sb.append(e.getKey()).append(',').append(e.getValue()).append('\n');
        }
        write(file, sb.toString());
    }

    /** JSONL export of one dimension: one {@code {"dimension":...,"key":...,"cost_micros":...}} per line. */
    public synchronized void exportJsonl(BudgetDimension dimension, Path file) throws IOException {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(file, "file");
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Long> e
                : breakdown.getOrDefault(dimension, new LinkedHashMap<>()).entrySet()) {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("dimension", dimension.name());
            node.put("key", e.getKey());
            node.put("cost_micros", e.getValue());
            sb.append(node).append('\n');
        }
        write(file, sb.toString());
    }

    private static void write(Path file, String content) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ============ One-line sink wiring ============

    /**
     * A {@link MetricsSink} that prices every model call and books the
     * microUSD to each configured dimension key - the fixed-attribution
     * shape (demo/pilot deployments). Metrics stay a side channel: unpriced
     * models are skipped with a warn, failures never propagate.
     */
    public static final class AttributionSink implements MetricsSink {

        private final CostMeter meter;
        private final Map<BudgetDimension, String> keys;
        private final CostDashboard dashboard = new CostDashboard();

        private AttributionSink(CostMeter meter, Map<BudgetDimension, String> keys) {
            this.meter = meter;
            this.keys = keys;
        }

        @Override
        public void onModelCall(ModelCallMetrics metrics) {
            long cost;
            try {
                cost = meter.costMicros(metrics);
            } catch (RuntimeException e) {
                log.warn("no pricing for model '{}', dashboard skips the cost ({})",
                        metrics.model(), e.getMessage());
                return;
            }
            dashboard.recordCost(cost);
            keys.forEach((dimension, key) -> dashboard.record(dimension, key, cost));
        }

        @Override
        public void onToolCall(io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics metrics) {
            // tools are free in v1 accounting - token cost lives on model calls
        }

        @Override
        public void onRun(io.github.qwzhang01.agent.observability.metrics.RunMetrics metrics) {
        }

        /** The dashboard this sink books into (assembly reads it for exports). */
        public CostDashboard dashboard() {
            return dashboard;
        }
    }

    /**
     * Wire the fixed-attribution sink: prices model calls via
     * {@link CostMeter}, books microUSD under each dimension key.
     *
     * @param meter       pricer (CostMeter's fail-loud contract applies to
     *                    direct callers; this adapter catches and skips)
     * @param attribution dimension -&gt; key to book under (e.g.
     *                    TENANT-&gt;"acme", USER-&gt;"alice"); must not be empty
     */
    public static AttributionSink attributionSink(CostMeter meter, Map<BudgetDimension, String> attribution) {
        Objects.requireNonNull(meter, "meter");
        Map<BudgetDimension, String> keys = Map.copyOf(Objects.requireNonNull(attribution, "attribution"));
        if (keys.isEmpty()) {
            throw new IllegalArgumentException("attribution must not be empty - nothing would be booked");
        }
        return new AttributionSink(meter, keys);
    }
}

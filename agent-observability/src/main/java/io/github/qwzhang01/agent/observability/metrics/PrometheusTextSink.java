package io.github.qwzhang01.agent.observability.metrics;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Prometheus text-exposition sink (Stage 7.2) - zero-dependency metrics
 * outlet in the 0.0.4 text format. Deployments scrape it from a servlet,
 * a plain HTTP handler, or a scheduled file write; the framework never
 * takes a Micrometer dependency to stay embeddable (same discipline as
 * D9's OTel verdict: stable interfaces first, ecosystems attach).
 * <p>
 * Exposure model: the sink aggregates counters in memory
 * ({@link #scrape} renders the snapshot). Series follow the framework's
 * three projections:
 * <ul>
 *   <li>{@code agent4j_model_calls_total{model,status}} + token counters
 *       (the Model boundary)</li>
 *   <li>{@code agent4j_tool_calls_total{tool,status,denied}} (the Tool
 *       boundary; a denied call is status="denied" - the leading
 *       injection indicator gets its own series)</li>
 *   <li>{@code agent4j_runs_total{agent,status}} + cost/token/duration
 *       gauges from {@link RunMetrics} rows</li>
 *   <li>{@code agent4j_orphan_events_total} - boundary events outside any
 *       run context; rising = wiring bug, not traffic</li>
 * </ul>
 * Label cardinality is bounded by construction: labels come from the
 * framework's finite enums and the deployment's model/tool names - never
 * from free text like error messages (a label explosion is a Grafana
 * outage waiting to happen).
 */
public final class PrometheusTextSink implements MetricsSink {

    // series -> value (TreeMap for stable scrape output)
    private final Map<String, Long> counters = new TreeMap<>();
    private long sinkFailures;

    @Override
    public synchronized void onModelCall(ModelCallMetrics metrics) {
        try {
            bump("agent4j_model_calls_total", metrics.model(),
                    "status", metrics.success() ? "ok" : "error");
            if (metrics.success()) {
                bumpBy("agent4j_model_prompt_tokens_total", metrics.model(), metrics.promptTokens());
                bumpBy("agent4j_model_completion_tokens_total", metrics.model(), metrics.completionTokens());
                bumpBy("agent4j_model_cached_tokens_total", metrics.model(), metrics.cachedTokens());
                if (metrics.latencyMs() >= 0) {
                    observeLatency("agent4j_model_latency_ms", metrics.model(), metrics.latencyMs());
                }
            }
        } catch (RuntimeException e) {
            sinkFailures++;
        }
    }

    @Override
    public synchronized void onToolCall(ToolCallMetrics metrics) {
        try {
            String status = metrics.denied() ? "denied"
                    : metrics.success() ? "ok" : "error";
            bump("agent4j_tool_calls_total", metrics.toolName(),
                    "status", status, "denied", String.valueOf(metrics.denied()));
            if (metrics.latencyMs() >= 0) {
                observeLatency("agent4j_tool_latency_ms", metrics.toolName(), metrics.latencyMs());
            }
        } catch (RuntimeException e) {
            sinkFailures++;
        }
    }

    @Override
    public synchronized void onRun(RunMetrics metrics) {
        try {
            bump("agent4j_runs_total", metrics.agentName(),
                    "status", metrics.status().name());
            counters.merge("agent4j_run_cost_micros_total{agent=\"" + label(metrics.agentName()) + "\"}",
                    metrics.costMicros(), Long::sum);
            counters.merge("agent4j_run_tokens_total{agent=\"" + label(metrics.agentName()) + "\"}",
                    (long) metrics.tokenUsage().totalTokens(), Long::sum);
            if (metrics.durationMs() >= 0) {
                counters.merge("agent4j_run_duration_ms_sum{agent=\"" + label(metrics.agentName()) + "\"}",
                        metrics.durationMs(), Long::sum);
                counters.merge("agent4j_run_duration_ms_count{agent=\"" + label(metrics.agentName()) + "\"}",
                        1L, Long::sum);
            }
            if (!metrics.succeeded()) {
                bump("agent4j_run_failures_total", metrics.agentName());
            }
        } catch (RuntimeException e) {
            sinkFailures++;
        }
    }

    /** Boundary event seen with no active run context. */
    public synchronized void onOrphanEvent() {
        counters.merge("agent4j_orphan_events_total", 1L, Long::sum);
    }

    // ============ Latency quantiles (P50/P95/P99 per label set) ============

    private final Map<String, java.util.List<Long>> latencies = new TreeMap<>();

    private void observeLatency(String family, String labelValue, long ms) {
        latencies.computeIfAbsent(family + "{name=\"" + label(labelValue) + "\"}",
                k -> new java.util.ArrayList<>()).add(ms);
    }

    /** Render P50/P95/P99 lines for the latency families. */
    private synchronized void renderQuantiles(StringBuilder out) {
        for (Map.Entry<String, List<Long>> e : latencies.entrySet()) {
            List<Long> sorted = new java.util.ArrayList<>(e.getValue());
            java.util.Collections.sort(sorted);
            out.append(quantileLine(e.getKey(), sorted, 0.50, "p50"));
            out.append(quantileLine(e.getKey(), sorted, 0.95, "p95"));
            out.append(quantileLine(e.getKey(), sorted, 0.99, "p99"));
        }
    }

    private static String quantileLine(String series, List<Long> sorted, double q, String tag) {
        // quantile on the stored sample (honest sample quantile, not a
        // histogram estimate - deployments wanting histograms wrap this)
        int idx = (int) Math.ceil(q * sorted.size()) - 1;
        long value = sorted.isEmpty() ? 0 : sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
        // Prometheus 0.0.4 summary quantile line: labels carry quantile="0.5"
        // alongside the family's own labels - NOT a bare "p50" middle token
        // (that renders an unparsable line and breaks scrapers).
        String quantile = tag.equals("p50") ? "0.5" : tag.equals("p95") ? "0.95" : "0.99";
        String labeled = series.endsWith("}")
                ? series.substring(0, series.length() - 1) + ",quantile=\"" + quantile + "\"}"
                : series + "{quantile=\"" + quantile + "\"}";
        return labeled + " " + value + "\n";
    }

    // ============ Helpers ============

    private void bump(String family, String labelValue, String... extraPairs) {
        String labels = buildLabels(labelValue, extraPairs);
        counters.merge(family + labels, 1L, Long::sum);
    }

    private void bumpBy(String family, String labelValue, long delta) {
        counters.merge(family + "{name=\"" + label(labelValue) + "\"}", delta, Long::sum);
    }

    private static String buildLabels(String labelValue, String... extraPairs) {
        StringBuilder sb = new StringBuilder("{name=\"").append(label(labelValue)).append('"');
        for (int i = 0; i + 1 < extraPairs.length; i += 2) {
            sb.append(',').append(extraPairs[i]).append("=\"")
                    .append(label(extraPairs[i + 1])).append('"');
        }
        return sb.append('}').toString();
    }

    /** Escape a label value per the text-format spec (backslash, quote, newline). */
    private static String label(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * Render the full exposition snapshot (counter families + quantiles).
     * Format follows the Prometheus 0.0.4 text exposition format.
     */
    public synchronized String scrape() {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> e : counters.entrySet()) {
            out.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        renderQuantiles(out);
        out.append("agent4j_sink_failures_total ").append(sinkFailures).append('\n');
        return out.toString();
    }

    /** How many sink callbacks failed internally (side-channel discipline, counted). */
    public synchronized long sinkFailures() {
        return sinkFailures;
    }
}

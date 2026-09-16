package io.github.qwzhang01.agent.observability.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.2 acceptance for the Prometheus text-exposition sink:
 * every scraped line must be a parsable 0.0.0.4 exposition line, and
 * the quantile rendering must carry {@code quantile="..."} labels
 * (the format bug batch 2 wrote this test to catch).
 */
class PrometheusTextSinkTest {

    @Test
    void modelCallRendersCallsTokensAndLatencyQuantiles() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onModelCall(new ModelCallMetrics("gpt-4o", 120, 100, 40, 140, 0, "stop", null));
        sink.onModelCall(new ModelCallMetrics("gpt-4o", 480, 100, 40, 140, 0, "stop", null));

        String scrape = sink.scrape();
        assertTrue(scrape.contains(
                "agent4j_model_calls_total{name=\"gpt-4o\",status=\"ok\"} 2"), scrape);
        assertTrue(scrape.contains("agent4j_model_prompt_tokens_total{name=\"gpt-4o\"} 200"), scrape);
        assertTrue(scrape.contains("agent4j_model_completion_tokens_total{name=\"gpt-4o\"} 80"), scrape);

        // quantile lines must be legal summary format: quantile label, no bare p50 token
        assertTrue(scrape.contains("agent4j_model_latency_ms{name=\"gpt-4o\",quantile=\"0.5\"} "), scrape);
        assertTrue(scrape.contains("agent4j_model_latency_ms{name=\"gpt-4o\",quantile=\"0.95\"} 480"), scrape);
        assertTrue(scrape.contains("agent4j_model_latency_ms{name=\"gpt-4o\",quantile=\"0.99\"} 480"), scrape);
        assertFalse(scrape.contains(" p50 "), "bare p50 token is not valid exposition format: " + scrape);

        // every non-quantile line: exactly one space between series and value
        for (String line : scrape.split("\n")) {
            if (line.isBlank() || line.contains("quantile=")) {
                continue;
            }
            String[] parts = line.split(" ");
            assertEquals(2, parts.length, "line must be 'series value': " + line);
            assertTrue(Long.parseLong(parts[1]) >= 0, "value must be a long: " + line);
        }
    }

    @Test
    void failedModelCallCountsErrorSeriesWithoutTokenCounters() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onModelCall(ModelCallMetrics.failure("gpt-4o", 50, "boom"));

        String scrape = sink.scrape();
        assertTrue(scrape.contains("agent4j_model_calls_total{name=\"gpt-4o\",status=\"error\"} 1"), scrape);
        assertFalse(scrape.contains("agent4j_model_prompt_tokens_total"), scrape);
    }

    @Test
    void toolCallSeparatesDeniedFromOkAndError() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onToolCall(new ToolCallMetrics("search", 30, true, false, null));
        sink.onToolCall(new ToolCallMetrics("search", 30, false, true, null));   // denied
        sink.onToolCall(new ToolCallMetrics("search", 30, false, false, "threw")); // error

        String scrape = sink.scrape();
        assertTrue(scrape.contains("agent4j_tool_calls_total{name=\"search\",status=\"ok\",denied=\"false\"} 1"), scrape);
        assertTrue(scrape.contains("agent4j_tool_calls_total{name=\"search\",status=\"denied\",denied=\"true\"} 1"), scrape);
        assertTrue(scrape.contains("agent4j_tool_calls_total{name=\"search\",status=\"error\",denied=\"false\"} 1"), scrape);
    }

    @Test
    void runRowRendersTotalsCostDurationAndFailures() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onRun(runMetrics("run-1", AgentStatusForTest.DONE, 300));

        String scrape = sink.scrape();
        assertTrue(scrape.contains("agent4j_runs_total{name=\"support\",status=\"DONE\"} 1"), scrape);
        assertTrue(scrape.contains("agent4j_run_cost_micros_total{agent=\"support\"} 12000"), scrape);
        assertTrue(scrape.contains("agent4j_run_tokens_total{agent=\"support\"} 140"), scrape);
        assertTrue(scrape.contains("agent4j_run_duration_ms_sum{agent=\"support\"} 300"), scrape);
        assertTrue(scrape.contains("agent4j_run_duration_ms_count{agent=\"support\"} 1"), scrape);
        assertFalse(scrape.contains("agent4j_run_failures_total"), scrape);
    }

    @Test
    void failedRunAddsFailureSeries() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onRun(runMetrics("run-2", AgentStatusForTest.ERROR, 0));

        String scrape = sink.scrape();
        assertTrue(scrape.contains("agent4j_run_failures_total{name=\"support\"} 1"), scrape);
    }

    @Test
    void orphanEventsHaveTheirOwnSeries() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onOrphanEvent();
        sink.onOrphanEvent();

        String scrape = sink.scrape();
        assertTrue(scrape.contains("agent4j_orphan_events_total 2"), scrape);
    }

    @Test
    void labelValuesAreEscapedPerTextFormatSpec() {
        PrometheusTextSink sink = new PrometheusTextSink();
        sink.onModelCall(new ModelCallMetrics("weird\"model\\name", 10, 1, 1, 2, 0, "stop", null));

        String scrape = sink.scrape();
        assertTrue(scrape.contains("name=\"weird\\\"model\\\\name\""), scrape);
    }

    // ============ Helpers ============

    private static RunMetrics runMetrics(String runId, AgentStatusForTest status, long durationMs) {
        return new RunMetrics(
                runId,
                "support",
                status.status(),
                status == AgentStatusForTest.ERROR ? "kaput" : null,
                durationMs,
                1, 0, 1, 0,
                new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(100, 40, 140, 0),
                12_000L);
    }

    /** Tiny enum wrapper so the helper reads cleanly. */
    private enum AgentStatusForTest {
        DONE(io.github.qwzhang01.agent.core.agent.AgentState.Status.DONE),
        ERROR(io.github.qwzhang01.agent.core.agent.AgentState.Status.ERROR);

        private final io.github.qwzhang01.agent.core.agent.AgentState.Status status;

        AgentStatusForTest(io.github.qwzhang01.agent.core.agent.AgentState.Status status) {
            this.status = status;
        }

        io.github.qwzhang01.agent.core.agent.AgentState.Status status() {
            return status;
        }
    }
}

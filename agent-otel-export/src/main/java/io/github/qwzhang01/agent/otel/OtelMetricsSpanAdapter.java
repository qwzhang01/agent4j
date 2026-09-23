package io.github.qwzhang01.agent.otel;

import io.github.qwzhang01.agent.observability.metrics.MetricsSink;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Objects;

/**
 * Model/Tool boundary metrics-to-span adapter : every model call
 * becomes a {@code agent.model} span, every tool call a {@code agent.tool}
 * span - the two boundaries the on-call engineer greps first.
 * <p>
 * Attributes are numbers and names only (token counts, latency, model id,
 * tool name, denied flag). Prompt text and tool args/results are never
 * attached - the sensitive-content rule is the same as
 * {@link OtelRunEventSpanAdapter}'s: structure yes, content no.
 * <p>
 * Boundary spans do not nest under run spans here: the MetricsSink
 * contract carries no runId (the boundary may fire outside any run - the
 * orphan-attribution discipline of MetricsCollector). Nesting happens at
 * the deployment's OTel context propagation, which the SDK resolves from
 * the ambient context; this adapter stays honest about what the sink
 * contract actually knows.
 */
public final class OtelMetricsSpanAdapter implements MetricsSink {

    public static final String MODEL_SPAN_NAME = "agent.model";
    public static final String TOOL_SPAN_NAME = "agent.tool";

    private final Tracer tracer;
    private long sinkFailures;

    public OtelMetricsSpanAdapter(OpenTelemetry otel) {
        this.tracer = Objects.requireNonNull(otel, "otel").getTracer("agent4j.harness");
    }

    @Override
    public void onModelCall(ModelCallMetrics metrics) {
        try {
            Span span = tracer.spanBuilder(MODEL_SPAN_NAME)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("agent4j.model.id", metrics.model())
                    .setAttribute("agent4j.model.latency_ms", metrics.latencyMs())
                    .setAttribute("agent4j.model.prompt_tokens", metrics.promptTokens())
                    .setAttribute("agent4j.model.completion_tokens", metrics.completionTokens())
                    .setAttribute("agent4j.model.cached_tokens", metrics.cachedTokens())
                    .startSpan();
            if (metrics.finishReason() != null) {
                span.setAttribute("agent4j.model.finish_reason", metrics.finishReason());
            }
            if (metrics.success()) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR, metrics.error());
            }
            span.end();
        } catch (RuntimeException e) {
            sinkFailures++;
        }
    }

    @Override
    public void onToolCall(ToolCallMetrics metrics) {
        try {
            Span span = tracer.spanBuilder(TOOL_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("agent4j.tool.name", metrics.toolName())
                    .setAttribute("agent4j.tool.latency_ms", metrics.latencyMs())
                    .setAttribute("agent4j.tool.denied", metrics.denied())
                    .startSpan();
            if (metrics.success()) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR,
                        metrics.error() != null ? metrics.error() : "denied");
            }
            span.end();
        } catch (RuntimeException e) {
            sinkFailures++;
        }
    }

    @Override
    public void onRun(RunMetrics metrics) {
        // run-level span comes from the RunEvent adapter; this sink carries
        // the metrics projection, not the lifecycle projection
    }

    /** How many sink callbacks failed (side-channel discipline, counted). */
    public long sinkFailures() {
        return sinkFailures;
    }
}

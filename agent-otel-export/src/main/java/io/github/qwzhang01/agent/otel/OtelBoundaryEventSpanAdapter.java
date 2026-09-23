package io.github.qwzhang01.agent.otel;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * BoundaryEvent-to-span adapter (harness 4.4, 's five spans:
 * Workflow / Memory / Sandbox / MCP / A2A on top of the existing
 * run/step/model/tool four).
 * <p>
 * What this adapter covers TODAY, honestly:
 * <ul>
 *   <li>{@code agent.memory} span per {@link BoundaryEvent.MemoryAccessed};
 *       a {@link BoundaryEvent.MemoryAccessFailed} closes a one-shot span
 *       with ERROR status (a refusal is an observable outcome, not a gap).</li>
 *   <li>{@code agent.sandbox} span per {@link BoundaryEvent.SandboxExecuted},
 *       with escalation and refusal as one-shot ERROR-marked spans of their
 *       own kinds ({@code agent.sandbox.escalate} / {@code agent.sandbox.refuse}).</li>
 *   <li>{@code agent.approval} span per {@link BoundaryEvent.ApprovalDecided};
 *       {@link BoundaryEvent.ApprovalRefused} likewise one-shot ERROR.</li>
 *   <li>{@code agent.model.serving} span per
 *       {@link BoundaryEvent.ModelServingFinished} (the host-facing aggregate;
 *       the loop-facing twin is the metrics adapter's {@code agent.model}).</li>
 *   <li>{@code agent.mcp} span per {@link BoundaryEvent.McpToolCallFinished};
 *       {@link BoundaryEvent.McpToolCallFailed} is a one-shot ERROR span —
 *       a refusal is an observable outcome, not a gap (harness batch 7).</li>
 *   <li>{@code agent.a2a} span per {@link BoundaryEvent.A2ATaskSent};
 *       {@link BoundaryEvent.A2ATaskFailed} likewise one-shot ERROR.</li>
 * </ul>
 * Workflow spans come from {@link OtelRunEventSpanAdapter} (run/step); the
 * Model loop path comes from {@link OtelMetricsSpanAdapter}. MCP and A2A
 * spans land here since harness batch 7 wired the upstream emitters
 * (McpToolAdapter / InProcessA2AClient); the HTTP A2A client's emitter and
 * trace-context propagation across servers remain open (see limitations).
 * This adapter stays honest about what the contracts actually carry.
 * <p>
 * Structural-attribute discipline is identical to the sibling adapters:
 * ids, kinds, counts, durations - never memory content, approval payloads,
 * or sandboxed code.
 * <p>
 * Side-channel discipline: a throwing sink is swallowed and counted
 * ({@link #sinkFailures}), never propagated to the boundary that emitted.
 */
public final class OtelBoundaryEventSpanAdapter implements Consumer<BoundaryEvent> {

    /** Span name for the memory boundary. */
    public static final String MEMORY_SPAN_NAME = "agent.memory";
    /** Span name for the sandbox execution boundary. */
    public static final String SANDBOX_SPAN_NAME = "agent.sandbox";
    /** Span name for the sandbox escalation path. */
    public static final String SANDBOX_ESCALATE_SPAN_NAME = "agent.sandbox.escalate";
    /** Span name for the sandbox refusal path. */
    public static final String SANDBOX_REFUSE_SPAN_NAME = "agent.sandbox.refuse";
    /** Span name for the approval boundary. */
    public static final String APPROVAL_SPAN_NAME = "agent.approval";
    /** Span name for the host-facing model serving aggregate. */
    public static final String MODEL_SERVING_SPAN_NAME = "agent.model.serving";
    public static final String MCP_SPAN_NAME = "agent.mcp";
    public static final String A2A_SPAN_NAME = "agent.a2a";

    private final Tracer tracer;
    private long sinkFailures;

    public OtelBoundaryEventSpanAdapter(OpenTelemetry otel) {
        this.tracer = Objects.requireNonNull(otel, "otel").getTracer("agent4j.harness");
    }

    @Override
    public void accept(BoundaryEvent event) {
        try {
            translate(event);
        } catch (RuntimeException e) {
            // telemetry is a side channel: never break the boundary that emitted
            sinkFailures++;
        }
    }

    private void translate(BoundaryEvent event) {
        if (event instanceof BoundaryEvent.MemoryAccessed e) {
            Span span = tracer.spanBuilder(MEMORY_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("agent4j.memory.operation", e.operation())
                    .setAttribute("agent4j.memory.result_count", e.resultCount())
                    .setAttribute("agent4j.memory.masked", e.masked())
                    .setAttribute("agent4j.memory.duration_ms", e.durationMs())
                    .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        } else if (event instanceof BoundaryEvent.MemoryAccessFailed e) {
            // The refusal reason is free text (the store's exception
            // message) — audit-ledger content, never a span attribute.
            oneShotError(MEMORY_SPAN_NAME,
                    "agent4j.memory.operation", e.operation());
        } else if (event instanceof BoundaryEvent.SandboxExecuted e) {
            Span span = tracer.spanBuilder(SANDBOX_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("agent4j.sandbox.tier", e.tier())
                    .setAttribute("agent4j.sandbox.class_name", e.className())
                    .setAttribute("agent4j.sandbox.success", e.success())
                    .setAttribute("agent4j.sandbox.duration_ms", e.durationMs())
                    .startSpan();
            if (e.success()) {
                span.setStatus(StatusCode.OK);
            } else {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        } else if (event instanceof BoundaryEvent.SandboxEscalated e) {
            oneShot(SANDBOX_ESCALATE_SPAN_NAME, StatusCode.OK,
                    "agent4j.sandbox.class_name", e.className(),
                    "agent4j.sandbox.budget_used_for", e.budgetUsedFor());
        } else if (event instanceof BoundaryEvent.SandboxRefused e) {
            oneShotError(SANDBOX_REFUSE_SPAN_NAME,
                    "agent4j.sandbox.refusal_kind", e.refusalKind(),
                    "agent4j.sandbox.class_name", e.className());
        } else if (event instanceof BoundaryEvent.ApprovalDecided e) {
            Span span = tracer.spanBuilder(APPROVAL_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("agent4j.approval.id", e.approvalId())
                    .setAttribute("agent4j.approval.run_id", e.runId())
                    .setAttribute("agent4j.approval.decision", e.decision())
                    .setAttribute("agent4j.approval.decided_by", e.decidedBy())
                    .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        } else if (event instanceof BoundaryEvent.ApprovalRefused e) {
            // Refusal reason is the store's exception text — structure (the
            // id) yes, free text no.
            oneShotError(APPROVAL_SPAN_NAME, "agent4j.approval.id", e.approvalId());
        } else if (event instanceof BoundaryEvent.ModelServingFinished e) {
            Span span = tracer.spanBuilder(MODEL_SERVING_SPAN_NAME)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("agent4j.model.id", e.modelId())
                    .setAttribute("agent4j.model.latency_ms", e.latencyMs())
                    .setAttribute("agent4j.model.tokens", e.tokenCount())
                    .startSpan();
            if (e.failed()) {
                span.setStatus(StatusCode.ERROR, e.failureKind());
            } else {
                span.setStatus(StatusCode.OK);
            }
            span.end();
        } else if (event instanceof BoundaryEvent.ModelServingStarted e) {
            // The serving pair's start carries only the model id; the span
            // opens at finish when latency and outcome are known. Dropped
            // starts are the aggregate's honest trade: no fabricated
            // durations.
            // Intentionally not translated (see class javadoc).
            sinkFailures = sinkFailures; // no-op, documents the drop decision
        } else if (event instanceof BoundaryEvent.McpToolCallFinished e) {
            Span span = tracer.spanBuilder(MCP_SPAN_NAME)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("agent4j.mcp.server", e.serverName())
                    .setAttribute("agent4j.mcp.tool", e.toolName())
                    .setAttribute("agent4j.mcp.duration_ms", e.latencyMs())
                    .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        } else if (event instanceof BoundaryEvent.McpToolCallFailed e) {
            oneShotError(MCP_SPAN_NAME,
                    "agent4j.mcp.server", e.serverName(),
                    "agent4j.mcp.tool", e.toolName(),
                    "agent4j.mcp.failure_kind", e.failureKind());
        } else if (event instanceof BoundaryEvent.A2ATaskSent e) {
            Span span = tracer.spanBuilder(A2A_SPAN_NAME)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("agent4j.a2a.task_id", e.taskId())
                    .setAttribute("agent4j.a2a.recipient", e.recipient())
                    .setAttribute("agent4j.a2a.duration_ms", e.latencyMs())
                    .startSpan();
            span.setStatus(StatusCode.OK);
            span.end();
        } else if (event instanceof BoundaryEvent.A2ATaskFailed e) {
            oneShotError(A2A_SPAN_NAME,
                    "agent4j.a2a.task_id", e.taskId(),
                    "agent4j.a2a.recipient", e.recipient(),
                    "agent4j.a2a.failure_kind", e.failureKind());
        }
    }

    private void oneShot(String name, StatusCode status, String... attrs) {
        Span span = tracer.spanBuilder(name)
                .setSpanKind(SpanKind.INTERNAL)
                .startSpan();
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            span.setAttribute(attrs[i], attrs[i + 1]);
        }
        span.setStatus(status);
        span.end();
    }

    private void oneShotError(String name, String... attrs) {
        oneShot(name, StatusCode.ERROR, attrs);
    }

    /** How many sink callbacks failed (side-channel discipline, counted). */
    public long sinkFailures() {
        return sinkFailures;
    }
}

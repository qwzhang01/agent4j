package io.github.qwzhang01.agent.otel;

import io.github.qwzhang01.agent.core.run.RunEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * RunEvent-to-span adapter (Stage 7.1, blueprint D9's cashed promise: the
 * SDK finally arrives as a THIN shell over the stable contracts).
 * <p>
 * Translation rules (deliberately conservative):
 * <ul>
 *   <li>{@code RunStarted} opens a run span named {@code agent.run}; every
 *       step of the same runId nests under it via span context carry.</li>
 *   <li>{@code StepStarted}/{@code StepCompleted} pair opens/closes a
 *       {@code agent.step} child span; StepCompleted without a matching
 *       StepStarted is dropped (a step we never saw start never started -
 *       honest, no fabrication).</li>
 *   <li>{@code RunCompleted} closes the run span OK; {@code RunFailed}
 *       closes it ERROR with the failure kind as status description;
 *       {@code RunCanceled} closes it with status UNSET and a
 *       {@code run.canceled=true} attribute (cancellation is control flow,
 *       not an error - same discipline as FailureKind semantics).</li>
 *   <li>{@code RunPaused}/{@code RunResumed} add timeline events on the
 *       run span, not new spans - a pause is not work.</li>
 * </ul>
 * Sensitive-content discipline (roadmap 7.1): the adapter records ONLY
 * structural attributes (ids, kinds, counts, durations). Prompts, tool
 * args and results are never attached - consumers wanting content-level
 * tracing must opt in at the trajectory layer, where Stage 5's
 * redaction policy already governs what leaves the process.
 * <p>
 * Orphan policy: a terminal event for an unknown runId is counted
 * ({@link #orphanEvents}) and dropped - the adapter must never invent a
 * span parent out of thin air. Dropped-event count is queryable so the
 * "did we lose facts?" question has a number, not a shrug.
 */
public final class OtelRunEventSpanAdapter implements Consumer<RunEvent> {

    /** Span name for the run-level span. */
    public static final String RUN_SPAN_NAME = "agent.run";
    /** Span name for step-level child spans. */
    public static final String STEP_SPAN_NAME = "agent.step";

    private final Tracer tracer;
    private final Map<String, Span> runSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> stepSpans = new ConcurrentHashMap<>();
    private long orphanEvents;

    public OtelRunEventSpanAdapter(OpenTelemetry otel) {
        this.tracer = Objects.requireNonNull(otel, "otel").getTracer("agent4j.harness");
    }

    @Override
    public void accept(RunEvent event) {
        try {
            translate(event);
        } catch (RuntimeException e) {
            // telemetry is a side channel: never break the run being traced
            orphanEvents++;
        }
    }

    private void translate(RunEvent event) {
        if (event instanceof RunEvent.RunStarted started) {
            Span span = tracer.spanBuilder(RUN_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute("agent4j.run.id", started.runId())
                    .setAttribute("agent4j.trace.id", started.traceId())
                    .setAttribute("agent4j.schema.version", started.schemaVersion())
                    .startSpan();
            runSpans.put(started.runId(), span);
        } else if (event instanceof RunEvent.StepStarted step) {
            Span run = runSpans.get(step.runId());
            if (run == null) {
                orphanEvents++;
                return;
            }
            Span span = tracer.spanBuilder(STEP_SPAN_NAME)
                    .setSpanKind(SpanKind.INTERNAL)
                    .setParent(io.opentelemetry.context.Context.current().with(run))
                    .setAttribute("agent4j.step.id", step.stepId())
                    .setAttribute("agent4j.step.index", step.stepIndex())
                    .setAttribute("agent4j.step.attempt", step.attempt())
                    .startSpan();
            stepSpans.put(step.runId() + ":" + step.stepId(), span);
        } else if (event instanceof RunEvent.StepCompleted step) {
            Span span = stepSpans.remove(step.runId() + ":" + step.stepId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.setAttribute("agent4j.step.duration_ms", step.durationMs());
            if (step.summary() != null) {
                span.setAttribute("agent4j.step.summary", step.summary());
            }
            if (step.failureKind() != null) {
                span.setAttribute("agent4j.step.failure_kind", step.failureKind().name());
                span.setStatus(StatusCode.ERROR, step.failureKind().name());
            }
            span.end();
        } else if (event instanceof RunEvent.RunCompleted completed) {
            Span span = runSpans.remove(completed.runId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.setAttribute("agent4j.run.duration_ms", completed.durationMs());
            span.setAttribute("agent4j.run.steps", completed.steps());
            span.setStatus(StatusCode.OK);
            span.end();
        } else if (event instanceof RunEvent.RunFailed failed) {
            Span span = runSpans.remove(failed.runId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.setStatus(StatusCode.ERROR,
                    failed.failureKind() + ": " + failed.message());
            span.recordException(new IllegalStateException(failed.message()));
            span.end();
        } else if (event instanceof RunEvent.RunCanceled canceled) {
            Span span = runSpans.remove(canceled.runId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.setAttribute("agent4j.run.canceled", true);
            span.addEvent("run.canceled", io.opentelemetry.api.common.Attributes.builder()
                    .put("reason", safe(canceled.reason())).build());
            span.end();
        } else if (event instanceof RunEvent.RunPaused paused) {
            Span span = runSpans.get(paused.runId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.addEvent("run.paused", io.opentelemetry.api.common.Attributes.builder()
                    .put("reason", safe(paused.reason())).build());
        } else if (event instanceof RunEvent.RunResumed resumed) {
            Span span = runSpans.get(resumed.runId());
            if (span == null) {
                orphanEvents++;
                return;
            }
            span.addEvent("run.resumed", io.opentelemetry.api.common.Attributes.builder()
                    .put("fromStep", safe(resumed.fromStep())).build());
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text;
    }

    /** How many events could not be attributed (dropped, not fabricated). */
    public long orphanEvents() {
        return orphanEvents;
    }

    /** Spans still open (runs started but not yet terminal). */
    public int openRunSpans() {
        return runSpans.size();
    }
}

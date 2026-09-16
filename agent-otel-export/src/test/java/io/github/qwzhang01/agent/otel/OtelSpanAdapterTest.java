package io.github.qwzhang01.agent.otel;

import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.run.RunEvent;
import io.github.qwzhang01.agent.observability.metrics.ModelCallMetrics;
import io.github.qwzhang01.agent.observability.metrics.ToolCallMetrics;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 7.1 acceptance: the adapter produces real SDK spans with the
 * documented hierarchy/attributes, orphans are counted not fabricated,
 * and the side-channel discipline holds (sink failure never throws).
 */
class OtelSpanAdapterTest {

    private SdkTracerProvider tracerProvider;
    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk otel;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        otel = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    @AfterEach
    void tearDown() {
        tracerProvider.close();
    }

    @Test
    void runLifecycleProducesRunAndStepSpansWithAttributes() {
        OtelRunEventSpanAdapter adapter = new OtelRunEventSpanAdapter(otel);
        adapter.accept(new RunEvent.RunStarted("r1", "t1", Instant.now()));
        adapter.accept(new RunEvent.StepStarted("r1", "t1", "step-1", 1, 1, Instant.now()));
        adapter.accept(new RunEvent.StepCompleted("r1", "t1", "step-1", 1, 1,
                42, "ok", null, Instant.now()));
        adapter.accept(new RunEvent.RunCompleted("r1", "t1", 100, 1, Instant.now()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData run = spans.stream()
                .filter(s -> s.getName().equals(OtelRunEventSpanAdapter.RUN_SPAN_NAME))
                .findFirst().orElseThrow();
        SpanData step = spans.stream()
                .filter(s -> s.getName().equals(OtelRunEventSpanAdapter.STEP_SPAN_NAME))
                .findFirst().orElseThrow();
        assertTrue(spans.size() >= 2, "run span + step span at minimum");
        assertEquals("r1", run.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.run.id")));
        assertEquals(100L, run.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("agent4j.run.duration_ms")));
        assertEquals(1, run.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("agent4j.run.steps")));
        assertEquals("step-1", step.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.step.id")));
        assertEquals(0, adapter.orphanEvents());
        assertEquals(0, adapter.openRunSpans(), "run span closed on RunCompleted");
    }

    @Test
    void failedRunClosesSpanWithErrorStatusAndFailureKind() {
        OtelRunEventSpanAdapter adapter = new OtelRunEventSpanAdapter(otel);
        adapter.accept(new RunEvent.RunStarted("r2", "t2", Instant.now()));
        adapter.accept(new RunEvent.RunFailed("r2", "t2", FailureKind.MODEL_FAILURE,
                "provider down", Instant.now()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, spans.get(0).getStatus().getStatusCode());
        assertTrue(spans.get(0).getStatus().getDescription().contains("MODEL_FAILURE"));
        assertEquals(0, adapter.orphanEvents());
    }

    @Test
    void canceledRunIsControlFlowNotError() {
        OtelRunEventSpanAdapter adapter = new OtelRunEventSpanAdapter(otel);
        adapter.accept(new RunEvent.RunStarted("r3", "t3", Instant.now()));
        adapter.accept(new RunEvent.RunCanceled("r3", "t3", "user asked", Instant.now()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        assertEquals(io.opentelemetry.api.trace.StatusCode.UNSET, spans.get(0).getStatus().getStatusCode());
        assertTrue(spans.get(0).getAttributes()
                .get(io.opentelemetry.api.common.AttributeKey.booleanKey("agent4j.run.canceled")));
    }

    @Test
    void terminalEventWithoutStartIsCountedNotFabricated() {
        OtelRunEventSpanAdapter adapter = new OtelRunEventSpanAdapter(otel);
        adapter.accept(new RunEvent.RunCompleted("ghost", "t4", 5, 1, Instant.now()));
        adapter.accept(new RunEvent.StepCompleted("ghost", "t4", "step-1", 1, 1,
                5, "orphan", null, Instant.now()));

        assertEquals(2, adapter.orphanEvents());
        assertTrue(exporter.getFinishedSpanItems().isEmpty(), "no span fabricated for an unknown run");
    }

    @Test
    void pausedRunAddsTimelineEventNotSpan() {
        OtelRunEventSpanAdapter adapter = new OtelRunEventSpanAdapter(otel);
        adapter.accept(new RunEvent.RunStarted("r5", "t5", Instant.now()));
        adapter.accept(new RunEvent.RunPaused("r5", "t5", "approval", Instant.now()));
        adapter.accept(new RunEvent.RunResumed("r5", "t5", "step-2", Instant.now()));
        adapter.accept(new RunEvent.RunCompleted("r5", "t5", 900, 3, Instant.now()));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size(), "pause/resume are timeline events on the run span, not spans");
        assertEquals(2, spans.get(0).getEvents().size());
        assertEquals("run.paused", spans.get(0).getEvents().get(0).getName());
        assertEquals("run.resumed", spans.get(0).getEvents().get(1).getName());
    }

    @Test
    void metricsAdapterProducesModelAndToolSpans() {
        OtelMetricsSpanAdapter adapter = new OtelMetricsSpanAdapter(otel);
        adapter.onModelCall(new ModelCallMetrics("gpt-4o", 120, 100, 50, 150, 20, "stop", null));
        adapter.onToolCall(new ToolCallMetrics("search", 30, true, false, null));
        adapter.onToolCall(new ToolCallMetrics("danger", 1, false, true, null));

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(3, spans.size());
        SpanData model = spans.stream()
                .filter(s -> s.getName().equals(OtelMetricsSpanAdapter.MODEL_SPAN_NAME))
                .findFirst().orElseThrow();
        assertEquals("gpt-4o", model.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.model.id")));
        assertEquals(120L, model.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("agent4j.model.latency_ms")));
        SpanData denied = spans.stream()
                .filter(s -> s.getName().equals(OtelMetricsSpanAdapter.TOOL_SPAN_NAME))
                .filter(s -> Boolean.TRUE.equals(s.getAttributes()
                        .get(io.opentelemetry.api.common.AttributeKey.booleanKey("agent4j.tool.denied"))))
                .findFirst().orElseThrow();
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, denied.getStatus().getStatusCode());
        assertEquals(0, adapter.sinkFailures());
    }

    @Test
    void contentIsNeverAttachedOnlyStructure() {
        OtelMetricsSpanAdapter adapter = new OtelMetricsSpanAdapter(otel);
        adapter.onModelCall(new ModelCallMetrics("m", 1, 1, 1, 2, 0,
                "stop", "error text with SECRETS"));
        adapter.onToolCall(new ToolCallMetrics("t", 1, true, false, null));

        for (SpanData span : exporter.getFinishedSpanItems()) {
            span.getAttributes().forEach((key, value) ->
                    assertFalse(String.valueOf(value).contains("SECRETS"),
                            "no content-level attribute may carry raw text"));
        }
    }
}

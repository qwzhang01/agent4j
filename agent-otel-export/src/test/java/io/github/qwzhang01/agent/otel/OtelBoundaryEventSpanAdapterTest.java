package io.github.qwzhang01.agent.otel;

import io.github.qwzhang01.agent.core.event.BoundaryEvent;
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
 * Harness 4.4 acceptance: the boundary adapter produces real SDK spans for
 * the four families, refusals carry ERROR status, structure-only attributes
 * hold, and the side-channel discipline never throws.
 */
class OtelBoundaryEventSpanAdapterTest {

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

    private List<SpanData> spans() {
        return exporter.getFinishedSpanItems();
    }

    @Test
    void memoryAccessedProducesSpanWithStructure() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.MemoryAccessed(
                "query", "context-recall", 3, true, 42, Instant.now()));

        assertEquals(1, spans().size());
        SpanData span = spans().get(0);
        assertEquals(OtelBoundaryEventSpanAdapter.MEMORY_SPAN_NAME, span.getName());
        assertEquals("query", span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.memory.operation")));
        assertEquals(3, span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey("agent4j.memory.result_count")));
        assertEquals(42L, span.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey("agent4j.memory.duration_ms")));
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, span.getStatus().getStatusCode());
        assertEquals(0, adapter.sinkFailures());
    }

    @Test
    void memoryRefusalIsAnObservableOutcomeNotAGap() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.MemoryAccessFailed(
                "query", "purpose must not be null/blank", Instant.now()));

        assertEquals(1, spans().size());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR,
                spans().get(0).getStatus().getStatusCode());
    }

    @Test
    void sandboxFamilyCoversExecutedEscalatedRefused() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.SandboxExecuted(
                "CLASSLOADER", "G", true, 5, Instant.now()));
        adapter.accept(new BoundaryEvent.SandboxEscalated(
                "G", "tenant-a", Instant.now()));
        adapter.accept(new BoundaryEvent.SandboxRefused(
                "BUDGET_SPENT", "G", Instant.now()));

        assertEquals(3, spans().size());
        assertTrue(spans().stream().anyMatch(s ->
                s.getName().equals(OtelBoundaryEventSpanAdapter.SANDBOX_SPAN_NAME)));
        assertTrue(spans().stream().anyMatch(s ->
                s.getName().equals(OtelBoundaryEventSpanAdapter.SANDBOX_ESCALATE_SPAN_NAME)));
        SpanData refused = spans().stream()
                .filter(s -> s.getName().equals(OtelBoundaryEventSpanAdapter.SANDBOX_REFUSE_SPAN_NAME))
                .findFirst().orElseThrow();
        assertEquals("BUDGET_SPENT", refused.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.sandbox.refusal_kind")));
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, refused.getStatus().getStatusCode());
    }

    @Test
    void approvalDecidedAndRefusedCarryIdsNotPayloads() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.ApprovalDecided(
                "ap-1", "run-1", "APPROVED", "boss", Instant.now()));
        adapter.accept(new BoundaryEvent.ApprovalRefused(
                "ap-1", "stale version", Instant.now()));

        assertEquals(2, spans().size());
        SpanData decided = spans().stream()
                .filter(s -> s.getName().equals(OtelBoundaryEventSpanAdapter.APPROVAL_SPAN_NAME))
                .findFirst().orElseThrow();
        assertEquals("ap-1", decided.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.approval.id")));
        assertEquals("APPROVED", decided.getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.stringKey("agent4j.approval.decision")));
        assertEquals(1, spans().stream()
                .filter(s -> s.getStatus().getStatusCode() == io.opentelemetry.api.trace.StatusCode.ERROR)
                .count(), "the refusal twin is ERROR-marked");
    }

    @Test
    void modelServingFinishProducesHostFacingSpan() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.ModelServingFinished(
                "gpt-4o", 120, 150, false, null, Instant.now()));
        adapter.accept(new BoundaryEvent.ModelServingFinished(
                "gpt-4o", 300, -1, true, "TIMEOUT", Instant.now()));

        List<SpanData> spans = spans();
        assertEquals(2, spans.size());
        assertEquals(OtelBoundaryEventSpanAdapter.MODEL_SERVING_SPAN_NAME, spans.get(0).getName());
        assertEquals(120L, spans.get(0).getAttributes().get(
                io.opentelemetry.api.common.AttributeKey.longKey("agent4j.model.latency_ms")));
        assertEquals(io.opentelemetry.api.trace.StatusCode.OK, spans.get(0).getStatus().getStatusCode());
        assertEquals(io.opentelemetry.api.trace.StatusCode.ERROR, spans.get(1).getStatus().getStatusCode());
        assertEquals("TIMEOUT", spans.get(1).getStatus().getDescription());
    }

    @Test
    void contentIsNeverAttachedOnlyStructure() {
        OtelBoundaryEventSpanAdapter adapter = new OtelBoundaryEventSpanAdapter(otel);
        adapter.accept(new BoundaryEvent.MemoryAccessed(
                "query", "recall SECRET-PURPOSE-TEXT", 1, true, 1, Instant.now()));
        adapter.accept(new BoundaryEvent.MemoryAccessFailed(
                "query", "refused because SECRET-REASON-TEXT", Instant.now()));
        adapter.accept(new BoundaryEvent.ApprovalRefused(
                "ap-1", "conflict SECRET-PAYLOAD", Instant.now()));

        for (SpanData span : spans()) {
            span.getAttributes().forEach((key, value) ->
                    assertFalse(String.valueOf(value).contains("SECRET"),
                            "no content-level attribute may carry raw text: " + key.getKey()));
        }
    }
}

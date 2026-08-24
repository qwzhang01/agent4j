package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ObservingModelClientTest {

    // ============ Test helpers ============

    /** Sink that records every event into lists for exact assertions. */
    static final class RecordingSink implements MetricsSink {
        final List<ModelCallMetrics> modelCalls = new ArrayList<>();
        final List<ToolCallMetrics> toolCalls = new ArrayList<>();
        final List<RunMetrics> runs = new ArrayList<>();

        @Override
        public void onModelCall(ModelCallMetrics metrics) {
            modelCalls.add(metrics);
        }

        @Override
        public void onToolCall(ToolCallMetrics metrics) {
            toolCalls.add(metrics);
        }

        @Override
        public void onRun(RunMetrics metrics) {
            runs.add(metrics);
        }
    }

    /** Fully controllable fake - fixed response, failure mode, stream content. */
    static final class FakeModelClient implements ModelClient {
        ModelResponse next;
        RuntimeException failWith;
        Stream<StreamEvent> nextStream;
        ModelRequest lastRequest;

        @Override
        public ModelResponse chat(ModelRequest request) {
            this.lastRequest = request;
            if (failWith != null) {
                throw failWith;
            }
            return next;
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            this.lastRequest = request;
            return nextStream;
        }
    }

    private static ModelRequest request(String model) {
        return ModelRequest.builder()
                .model(model)
                .addMessage(ChatMessage.user("hi"))
                .build();
    }

    private static ModelResponse response(int prompt, int completion) {
        return new ModelResponse("answer", null, "stop",
                new ModelResponse.TokenUsage(prompt, completion, prompt + completion));
    }

    // ============ chat ============

    @Test
    @DisplayName("chat: full passthrough - same response instance, identical request seen by delegate")
    void chatPassthrough() {
        FakeModelClient fake = new FakeModelClient();
        ModelResponse expected = response(100, 50);
        fake.next = expected;
        RecordingSink sink = new RecordingSink();
        ObservingModelClient client = ObservingModelClient.wrap(fake, sink);

        ModelRequest req = request("gpt-x");
        ModelResponse out = client.chat(req);

        assertSame(expected, out, "response must be the delegate's instance, untouched");
        assertSame(req, fake.lastRequest, "delegate must receive the identical request");
    }

    @Test
    @DisplayName("chat: metrics captured exactly - model/latency/tokens/finishReason")
    void chatMetricsExact() {
        FakeModelClient fake = new FakeModelClient();
        fake.next = response(100, 50);
        RecordingSink sink = new RecordingSink();
        ObservingModelClient client = ObservingModelClient.wrap(fake, sink);

        client.chat(request("gpt-x"));

        assertEquals(1, sink.modelCalls.size());
        ModelCallMetrics m = sink.modelCalls.get(0);
        assertEquals("gpt-x", m.model());
        assertTrue(m.latencyMs() >= 0, "latency is wall-clock, >= 0 always");
        assertEquals(100, m.promptTokens());
        assertEquals(50, m.completionTokens());
        assertEquals(150, m.totalTokens());
        assertEquals("stop", m.finishReason());
        assertNull(m.error());
        assertTrue(m.success());
    }

    @Test
    @DisplayName("chat: usage not reported by provider -> tokens honestly zero")
    void chatUsageNullIsZero() {
        FakeModelClient fake = new FakeModelClient();
        fake.next = ModelResponse.text("no usage here");
        RecordingSink sink = new RecordingSink();
        ObservingModelClient.wrap(fake, sink).chat(request("m"));

        ModelCallMetrics m = sink.modelCalls.get(0);
        assertEquals(0, m.promptTokens());
        assertEquals(0, m.completionTokens());
        assertEquals(0, m.totalTokens());
    }

    @Test
    @DisplayName("chat: delegate exception recorded as error metrics, then rethrown (record, never swallow)")
    void chatExceptionRecordedAndRethrown() {
        FakeModelClient fake = new FakeModelClient();
        fake.failWith = new IllegalStateException("boom");
        RecordingSink sink = new RecordingSink();
        ObservingModelClient client = ObservingModelClient.wrap(fake, sink);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> client.chat(request("m")));

        assertEquals("boom", thrown.getMessage());
        assertEquals(1, sink.modelCalls.size());
        ModelCallMetrics m = sink.modelCalls.get(0);
        assertNotNull(m.error());
        assertTrue(m.error().contains("boom"));
        assertFalse(m.success());
        assertEquals(0, m.totalTokens());
        assertNull(m.finishReason());
    }

    // ============ stream ============

    @Test
    @DisplayName("stream: event sequence passes through untouched; metrics emitted once on Done with its usage")
    void streamPassthroughAndDoneMetrics() {
        FakeModelClient fake = new FakeModelClient();
        ModelResponse finalResponse = response(200, 80);
        fake.nextStream = Stream.of(
                new StreamEvent.ContentDelta("he"),
                new StreamEvent.ContentDelta("llo"),
                new StreamEvent.Done(finalResponse));
        RecordingSink sink = new RecordingSink();
        ObservingModelClient client = ObservingModelClient.wrap(fake, sink);

        List<StreamEvent> consumed = client.stream(request("gpt-s")).toList();

        assertEquals(3, consumed.size());
        assertEquals(new StreamEvent.ContentDelta("he"), consumed.get(0));
        assertEquals(new StreamEvent.ContentDelta("llo"), consumed.get(1));
        assertEquals(new StreamEvent.Done(finalResponse), consumed.get(2));

        assertEquals(1, sink.modelCalls.size(), "exactly one metrics event per consumed stream");
        ModelCallMetrics m = sink.modelCalls.get(0);
        assertEquals("gpt-s", m.model());
        assertEquals(200, m.promptTokens());
        assertEquals(80, m.completionTokens());
        assertEquals(280, m.totalTokens());
        assertEquals("stop", m.finishReason());
        assertTrue(m.success());
    }

    @Test
    @DisplayName("stream: Error terminal event -> failure metrics exactly once")
    void streamErrorMetrics() {
        FakeModelClient fake = new FakeModelClient();
        fake.nextStream = Stream.of(
                new StreamEvent.ContentDelta("par"),
                new StreamEvent.Error("connection reset", null));
        RecordingSink sink = new RecordingSink();
        ObservingModelClient.wrap(fake, sink).stream(request("m")).toList();

        assertEquals(1, sink.modelCalls.size());
        ModelCallMetrics m = sink.modelCalls.get(0);
        assertEquals("connection reset", m.error());
        assertFalse(m.success());
        assertEquals(0, m.totalTokens());
    }

    @Test
    @DisplayName("stream: abandoned stream without terminal event emits nothing (lazy semantics preserved)")
    void streamNoTerminalNoMetrics() {
        FakeModelClient fake = new FakeModelClient();
        fake.nextStream = Stream.of(new StreamEvent.ContentDelta("dangling"));
        RecordingSink sink = new RecordingSink();

        ObservingModelClient.wrap(fake, sink).stream(request("m")).toList();

        assertEquals(0, sink.modelCalls.size(), "no Done/Error consumed -> no metrics");
    }

    // ============ sink isolation ============

    @Test
    @DisplayName("sink throwing must not break the observed call (metrics are a side channel)")
    void sinkFailureSwallowed() {
        FakeModelClient fake = new FakeModelClient();
        fake.next = response(10, 5);
        MetricsSink exploding = new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics metrics) {
                throw new IllegalStateException("sink is broken");
            }

            @Override
            public void onToolCall(ToolCallMetrics metrics) {
            }

            @Override
            public void onRun(RunMetrics metrics) {
            }
        };
        ObservingModelClient client = ObservingModelClient.wrap(fake, exploding);

        ModelResponse out = assertDoesNotThrow(() -> client.chat(request("m")));
        assertEquals("answer", out.content());
    }

    @Test
    @DisplayName("multiple sinks: forwarding sink fans out, both receive the same event")
    void multipleSinksFanOut() {
        FakeModelClient fake = new FakeModelClient();
        fake.next = response(30, 10);
        RecordingSink a = new RecordingSink();
        RecordingSink b = new RecordingSink();
        MetricsSink forwarding = new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics metrics) {
                a.onModelCall(metrics);
                b.onModelCall(metrics);
            }

            @Override
            public void onToolCall(ToolCallMetrics metrics) {
                a.onToolCall(metrics);
                b.onToolCall(metrics);
            }

            @Override
            public void onRun(RunMetrics metrics) {
                a.onRun(metrics);
                b.onRun(metrics);
            }
        };

        ObservingModelClient.wrap(fake, forwarding).chat(request("m"));

        assertEquals(1, a.modelCalls.size());
        assertEquals(1, b.modelCalls.size());
        assertEquals(a.modelCalls.get(0), b.modelCalls.get(0));
    }
}

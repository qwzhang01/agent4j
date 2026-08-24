package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ObservingToolExecutorTest {

    // ============ Test helpers ============

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

    static final class FakeToolExecutor implements ToolExecutor {
        String nextResult;
        RuntimeException failWith;

        @Override
        public String execute(ToolCall toolCall) {
            if (failWith != null) {
                throw failWith;
            }
            return nextResult;
        }
    }

    private static ToolCall call(String tool) {
        return ToolCall.of("c1", tool, (com.fasterxml.jackson.databind.JsonNode) null);
    }

    // ============ success / failure ============

    @Test
    @DisplayName("success: result passes through untouched, metrics success=true denied=false")
    void successPassthrough() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.nextResult = "tool says hi";
        RecordingSink sink = new RecordingSink();
        ObservingToolExecutor executor = ObservingToolExecutor.wrap(fake, sink);

        String out = executor.execute(call("get_weather"));

        assertEquals("tool says hi", out);
        assertEquals(1, sink.toolCalls.size());
        ToolCallMetrics m = sink.toolCalls.get(0);
        assertEquals("get_weather", m.toolName());
        assertTrue(m.latencyMs() >= 0);
        assertTrue(m.success());
        assertFalse(m.denied());
        assertNull(m.error());
    }

    @Test
    @DisplayName("executor exception: recorded as failure metrics, then rethrown")
    void exceptionRecordedAndRethrown() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.failWith = new IllegalStateException("tool exploded");
        RecordingSink sink = new RecordingSink();
        ObservingToolExecutor executor = ObservingToolExecutor.wrap(fake, sink);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> executor.execute(call("get_weather")));

        assertEquals("tool exploded", thrown.getMessage());
        ToolCallMetrics m = sink.toolCalls.get(0);
        assertFalse(m.success());
        assertFalse(m.denied(), "a framework-level failure is NOT a governance denial");
        assertNotNull(m.error());
        assertTrue(m.error().contains("tool exploded"));
    }

    // ============ governance denial contract (Stage 9 prefixes) ============

    @Test
    @DisplayName("[DENIED] prefix from the governance chain -> denied=true, success=false (tool never ran)")
    void deniedPrefixDetected() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.nextResult = "[DENIED] Tool 'run_command' is denied by policy";
        RecordingSink sink = new RecordingSink();
        ObservingToolExecutor executor = ObservingToolExecutor.wrap(fake, sink);

        String out = executor.execute(call("run_command"));

        assertEquals("[DENIED] Tool 'run_command' is denied by policy", out, "denial text passes through verbatim");
        ToolCallMetrics m = sink.toolCalls.get(0);
        assertTrue(m.denied());
        assertFalse(m.success());
    }

    @Test
    @DisplayName("[RATE_LIMITED] prefix is also a governance denial")
    void rateLimitedPrefixDetected() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.nextResult = "[RATE_LIMITED] Rate limit exceeded for tool 'search'";
        RecordingSink sink = new RecordingSink();

        ObservingToolExecutor.wrap(fake, sink).execute(call("search"));

        assertTrue(sink.toolCalls.get(0).denied());
        assertFalse(sink.toolCalls.get(0).success());
    }

    @Test
    @DisplayName("[ERROR] prefix (Stage 2 tool-error wrapping) is a normal observation, NOT a denial")
    void errorPrefixIsNotDenial() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.nextResult = "[ERROR] Tool 'get_weather' failed: timeout";
        RecordingSink sink = new RecordingSink();

        ObservingToolExecutor.wrap(fake, sink).execute(call("get_weather"));

        ToolCallMetrics m = sink.toolCalls.get(0);
        assertFalse(m.denied(), "the tool RAN and failed - that is a quality signal, not a governance signal");
        assertTrue(m.success(), "error-wrapped text is a normal observation the model saw");
    }

    // ============ sink isolation ============

    @Test
    @DisplayName("sink throwing must not break tool execution (metrics are a side channel)")
    void sinkFailureSwallowed() {
        FakeToolExecutor fake = new FakeToolExecutor();
        fake.nextResult = "ok";
        MetricsSink exploding = new MetricsSink() {
            @Override
            public void onModelCall(ModelCallMetrics metrics) {
                throw new IllegalStateException("sink is broken");
            }

            @Override
            public void onToolCall(ToolCallMetrics metrics) {
                throw new IllegalStateException("sink is broken");
            }

            @Override
            public void onRun(RunMetrics metrics) {
            }
        };
        ObservingToolExecutor executor = ObservingToolExecutor.wrap(fake, exploding);

        assertEquals("ok", assertDoesNotThrow(() -> executor.execute(call("t"))));
    }
}

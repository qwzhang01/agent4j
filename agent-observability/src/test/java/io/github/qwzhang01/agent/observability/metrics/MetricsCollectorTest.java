package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ReActAgentLoop;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.model.mock.MockModelClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MetricsCollectorTest {

    // ============ helpers ============

    private static ModelCallMetrics modelCall(int prompt, int completion, String error) {
        return new ModelCallMetrics("m", 1L, prompt, completion, prompt + completion,
                error == null ? "stop" : null, error);
    }

    private static ToolCallMetrics toolCall(boolean denied) {
        return new ToolCallMetrics("t", 1L, !denied, denied, null);
    }

    private MetricsCollector runOnce(String runId, String agent, AgentState.Status status) {
        MetricsCollector c = new MetricsCollector();
        c.beginRun(runId, agent);
        c.onModelCall(modelCall(100, 40, null));
        c.onToolCall(toolCall(false));
        c.endRun(status, status == AgentState.Status.ERROR ? "boom" : null);
        return c;
    }

    // ============ aggregation ============

    @Test
    @DisplayName("endRun materializes the exact aggregate: counts, tokens summed, denied counted")
    void aggregationExact() {
        MetricsCollector c = new MetricsCollector();
        c.beginRun("r1", "agent-a");
        c.onModelCall(modelCall(100, 40, null));
        c.onModelCall(modelCall(50, 10, "timeout"));   // error call still counted + tokens still summed
        c.onToolCall(toolCall(false));
        c.onToolCall(toolCall(true));                  // denied
        c.onToolCall(toolCall(false));

        RunMetrics m = c.endRun(AgentState.Status.DONE, null);

        assertEquals("r1", m.runId());
        assertEquals("agent-a", m.agentName());
        assertEquals(AgentState.Status.DONE, m.status());
        assertNull(m.lastError());
        assertTrue(m.durationMs() >= 0);
        assertEquals(2, m.modelCallCount());
        assertEquals(1, m.modelCallErrors());
        assertEquals(3, m.toolCallCount());
        assertEquals(1, m.deniedToolCalls());
        assertEquals(new ModelResponse.TokenUsage(150, 50, 200), m.tokenUsage());
        assertEquals(0L, m.costMicros(), "honest placeholder until M18.2 wires CostMeter");
        assertTrue(m.succeeded());
    }

    @Test
    @DisplayName("failure run: status/lastError carried into the summary row")
    void failureStatusCarried() {
        MetricsCollector c = new MetricsCollector();
        c.beginRun("r-fail", "agent-a");
        RunMetrics m = c.endRun(AgentState.Status.ERROR, "model timeout");
        assertEquals(AgentState.Status.ERROR, m.status());
        assertEquals("model timeout", m.lastError());
        assertFalse(m.succeeded());
    }

    @Test
    @DisplayName("endRun clears the thread context: second endRun fails fast")
    void endRunClearsContext() {
        MetricsCollector c = new MetricsCollector();
        c.beginRun("r1", "a");
        c.endRun(AgentState.Status.DONE, null);
        assertThrows(IllegalArgumentException.class,
                () -> c.endRun(AgentState.Status.DONE, null));
    }

    // ============ guards ============

    @Test
    @DisplayName("beginRun guards: blank ids rejected, nested run rejected, runId reuse rejected")
    void beginRunGuards() {
        MetricsCollector c = new MetricsCollector();
        assertThrows(IllegalArgumentException.class, () -> c.beginRun(" ", "a"));
        assertThrows(IllegalArgumentException.class, () -> c.beginRun("r1", null));

        c.beginRun("r1", "a");
        assertThrows(IllegalArgumentException.class, () -> c.beginRun("r2", "a"),
                "nested run on one thread is rejected (Stage 14 discipline)");

        c.endRun(AgentState.Status.DONE, null);
        assertThrows(IllegalArgumentException.class, () -> c.beginRun("r1", "a"),
                "runIds are unique, reuse rejected");
    }

    // ============ queries ============

    @Test
    @DisplayName("runMetrics: finished runs queryable, active/unknown return empty")
    void runMetricsQuery() {
        MetricsCollector c = new MetricsCollector();
        c.beginRun("r1", "a");
        assertTrue(c.runMetrics("r1").isEmpty(), "active run has no summary yet - honest, not partial");
        c.onModelCall(modelCall(10, 5, null));
        c.endRun(AgentState.Status.DONE, null);

        Optional<RunMetrics> found = c.runMetrics("r1");
        assertTrue(found.isPresent());
        assertEquals(1, found.get().modelCallCount());
        assertTrue(c.runMetrics("nope").isEmpty());
    }

    @Test
    @DisplayName("byAgent filters finished runs; other agents invisible")
    void byAgentFilters() {
        MetricsCollector a = runOnce("r1", "agent-a", AgentState.Status.DONE);
        a.beginRun("r2", "agent-b");
        a.endRun(AgentState.Status.DONE, null);

        List<RunMetrics> ofA = a.byAgent("agent-a");
        assertEquals(1, ofA.size());
        assertEquals("r1", ofA.get(0).runId());
        assertEquals(1, a.byAgent("agent-b").size());
        assertTrue(a.byAgent("agent-c").isEmpty());
    }

    @Test
    @DisplayName("agentStats: success rate over finished runs; MAX_STEPS counts as failed")
    void agentStatsRate() {
        MetricsCollector c = runOnce("r1", "a", AgentState.Status.DONE);
        c.beginRun("r2", "a");
        c.endRun(AgentState.Status.MAX_STEPS_EXCEEDED, null);
        c.beginRun("r3", "a");
        c.endRun(AgentState.Status.ERROR, "x");

        MetricsCollector.AgentStats s = c.agentStats("a");
        assertEquals(3, s.totalRuns());
        assertEquals(1, s.succeededRuns());
        assertEquals(2, s.failedRuns());
        assertEquals(1.0 / 3, s.successRate(), 1e-9);
    }

    @Test
    @DisplayName("orphan events (no run context) still count into global totals")
    void orphanEventsCounted() {
        MetricsCollector c = new MetricsCollector();
        c.onModelCall(modelCall(10, 5, null));   // no beginRun on this thread
        c.onToolCall(toolCall(true));

        assertEquals(1, c.totalModelCalls());
        assertEquals(1, c.totalToolCalls());

        c.beginRun("r1", "a");
        c.onModelCall(modelCall(20, 10, null));
        c.endRun(AgentState.Status.DONE, null);

        assertEquals(2, c.totalModelCalls(), "in-run + orphan");
        assertEquals(1, c.totalToolCalls());
        assertEquals(1, c.runMetrics("r1").orElseThrow().modelCallCount());
    }

    // ============ end-to-end: real ReActAgentLoop, zero loop changes ============

    @Test
    @DisplayName("end-to-end: SimpleAgent + both observing decorators + collector, one wiring line each")
    void endToEndWithRealLoop() {
        MockModelClient mock = MockModelClient.scripted();
        // turn 1: model asks to call the echo tool; turn 2: final answer with usage
        mock.respondToolCalls(ToolCall.of("c1", "echo", "{}"));
        mock.respond(new ModelResponse("all done", null, "stop",
                new ModelResponse.TokenUsage(100, 40, 140)));

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        registry.register(new Tool() {
            @Override
            public String getName() {
                return "echo";
            }

            @Override
            public String getDescription() {
                return "echoes its arguments";
            }

            @Override
            public String getParametersSchema() {
                return null;
            }

            @Override
            public String execute(JsonNode arguments) {
                return "echo:" + (arguments == null ? "" : arguments.toString());
            }
        });

        MetricsCollector collector = new MetricsCollector();
        ObservingModelClient model = ObservingModelClient.wrap(mock, collector);
        ObservingToolExecutor executor =
                ObservingToolExecutor.wrap(new DefaultToolExecutor(registry), collector);
        SimpleAgent agent = new SimpleAgent(
                new AgentConfig("obs-agent", null, model, registry, 5),
                new ReActAgentLoop(executor));

        AgentState state = new AgentState();
        collector.beginRun("run-e2e", "obs-agent");
        String answer = agent.run("hello", state);
        RunMetrics m = collector.endRun(state.getStatus(), state.getLastError());

        assertEquals("all done", answer);
        assertEquals(AgentState.Status.DONE, m.status());
        assertTrue(m.succeeded());
        assertEquals(2, m.modelCallCount(), "tool_calls turn + final answer turn");
        assertEquals(0, m.modelCallErrors());
        assertEquals(1, m.toolCallCount());
        assertEquals(0, m.deniedToolCalls());
        // first response reports no usage (0), second reports 100/40/140 - the sum is exact
        assertEquals(new ModelResponse.TokenUsage(100, 40, 140), m.tokenUsage());
        assertTrue(m.durationMs() >= 0);

        MetricsCollector.AgentStats stats = collector.agentStats("obs-agent");
        assertEquals(1, stats.totalRuns());
        assertEquals(1.0, stats.successRate(), 1e-9);
    }

    // ============ M18.2 wiring: cost accounting ============

    @Test
    @DisplayName("M18.2 wiring: CostMeter injected -> RunMetrics.costMicros is the exact priced sum")
    void costWiringExact() {
        io.github.qwzhang01.agent.observability.cost.PricingTable table =
                io.github.qwzhang01.agent.observability.cost.PricingTable.builder()
                        .price("premium", 2_500_000L, 10_000_000L)
                        .build();
        MetricsCollector c = new MetricsCollector(new io.github.qwzhang01.agent.observability.cost.CostMeter(table));
        c.beginRun("r-cost", "a");
        c.onModelCall(new ModelCallMetrics("premium", 1L, 100, 40, 140, "stop", null));   // 250+400=650
        c.onModelCall(new ModelCallMetrics("premium", 1L, 200, 100, 300, "stop", null));  // 500+1000=1500

        RunMetrics m = c.endRun(AgentState.Status.DONE, null);
        assertEquals(2150L, m.costMicros());
    }

    @Test
    @DisplayName("M18.2 wiring: unpriced model -> cost 0 + run survives (side-channel discipline)")
    void costWiringUnpricedSurvives() {
        io.github.qwzhang01.agent.observability.cost.PricingTable table =
                io.github.qwzhang01.agent.observability.cost.PricingTable.builder()
                        .price("known", 1_000_000L, 1_000_000L)
                        .build();
        MetricsCollector c = new MetricsCollector(new io.github.qwzhang01.agent.observability.cost.CostMeter(table));
        c.beginRun("r-cost2", "a");
        c.onModelCall(new ModelCallMetrics("mystery", 1L, 100, 40, 140, "stop", null));   // unpriced -> 0
        c.onModelCall(new ModelCallMetrics("known", 1L, 50, 50, 100, "stop", null));      // 50+50=100

        RunMetrics m = assertDoesNotThrow(() -> c.endRun(AgentState.Status.DONE, null));
        assertEquals(100L, m.costMicros(), "unpriced contributes 0 (warned), priced calls sum normally");
        assertEquals(2, m.modelCallCount(), "the unpriced call still counts as a call");
    }
}

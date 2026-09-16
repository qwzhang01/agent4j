package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.agent.AgentState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Stage 7.2 acceptance for the orphan queries: boundary events arriving
 * OUTSIDE any run context are counted separately - a rising orphan counter
 * is a wiring bug, not traffic, and operations must be able to see it.
 */
class MetricsCollectorOrphanTest {

    @Test
    void eventsOutsideAnyRunCountAsOrphansButStillIntoTotals() {
        MetricsCollector collector = new MetricsCollector();

        collector.onModelCall(new ModelCallMetrics("gpt-4o", 10, 10, 5, 15, 0, "stop", null));
        collector.onToolCall(new ToolCallMetrics("search", 5, true, false, null));

        assertEquals(1, collector.orphanModelCalls());
        assertEquals(1, collector.orphanToolCalls());
        assertEquals(1, collector.totalModelCalls(), "orphans still burn tokens: totals include them");
        assertEquals(1, collector.totalToolCalls());
        assertEquals(java.util.Optional.empty(), collector.runMetrics("anything"),
                "an orphan belongs to no run: nothing to materialize");
    }

    @Test
    void runAttributedEventsLeaveTheOrphanCountersAtZero() {
        MetricsCollector collector = new MetricsCollector();
        collector.beginRun("run-1", "support");
        collector.onModelCall(new ModelCallMetrics("gpt-4o", 10, 10, 5, 15, 0, "stop", null));
        collector.onToolCall(new ToolCallMetrics("search", 5, true, false, null));
        RunMetrics row = collector.endRun(AgentState.Status.DONE, null);

        assertEquals(0, collector.orphanModelCalls());
        assertEquals(0, collector.orphanToolCalls());
        assertEquals(1, collector.totalModelCalls());
        assertEquals(1, collector.totalToolCalls());
        assertEquals(1, row.modelCallCount(), "both events landed inside the run, attributed");
        assertEquals(1, row.toolCallCount());
    }
}

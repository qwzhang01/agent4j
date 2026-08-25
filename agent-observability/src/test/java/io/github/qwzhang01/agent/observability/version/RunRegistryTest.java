package io.github.qwzhang01.agent.observability.version;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RunRegistryTest {

    private static RunMetrics metrics(String runId, String agent) {
        return new RunMetrics(runId, agent, AgentState.Status.DONE, null, 42,
                2, 0, 1, 0, new ModelResponse.TokenUsage(100, 40, 140), 2_150);
    }

    // ============ ComponentVersion ============

    @Test
    @DisplayName("ComponentVersion: channel is nullable for kinds without a channel concept")
    void componentVersionChannel() {
        ComponentVersion prompt = new ComponentVersion(ComponentVersion.Kind.PROMPT,
                "support-system", "v3", "canary");
        ComponentVersion model = ComponentVersion.of(ComponentVersion.Kind.MODEL, "premium", "2026-08");

        assertEquals("canary", prompt.channel());
        assertNull(model.channel(), "models have no channel - absence is honest");
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentVersion(ComponentVersion.Kind.TOOL, " ", "f1", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ComponentVersion(ComponentVersion.Kind.TOOL, "core", "", null));
        assertThrows(NullPointerException.class,
                () -> new ComponentVersion(null, "core", "f1", null));
    }

    // ============ RunRecord ============

    @Test
    @DisplayName("RunRecord.of derives runId/agentName from the metrics row (they travel together)")
    void runRecordOfDerives() {
        RunRecord record = RunRecord.of(
                List.of(ComponentVersion.of(ComponentVersion.Kind.MODEL, "premium", "v1")),
                metrics("run-1", "assist"));

        assertEquals("run-1", record.runId());
        assertEquals("assist", record.agentName());
        assertEquals(2_150, record.metrics().costMicros());
    }

    @Test
    @DisplayName("combination(): human-readable triple with channel only where it exists")
    void combinationRendering() {
        RunRecord record = RunRecord.of(List.of(
                new ComponentVersion(ComponentVersion.Kind.PROMPT, "support-system", "v3", "canary"),
                ComponentVersion.of(ComponentVersion.Kind.MODEL, "cheap", "2026-08"),
                ComponentVersion.of(ComponentVersion.Kind.TOOL, "core", "f1")),
                metrics("run-8842", "assist"));

        assertEquals("PROMPT support-system@v3[canary], MODEL cheap@2026-08, TOOL core@f1",
                record.combination());
    }

    @Test
    @DisplayName("RunRecord guards: blank ids, null metrics")
    void runRecordGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunRecord(" ", "a", List.of(), metrics("r", "a")));
        assertThrows(IllegalArgumentException.class,
                () -> new RunRecord("r", null, List.of(), metrics("r", "a")));
        assertThrows(NullPointerException.class,
                () -> new RunRecord("r", "a", List.of(), null));
    }

    // ============ RunRegistry ============

    @Test
    @DisplayName("add/byRunId: the time-travel query answers 'what combination served this run'")
    void addAndQuery() {
        RunRegistry registry = new RunRegistry();
        registry.record(List.of(ComponentVersion.of(ComponentVersion.Kind.MODEL, "premium", "v1")),
                metrics("run-1", "assist"));

        Optional<RunRecord> found = registry.byRunId("run-1");
        assertTrue(found.isPresent());
        assertEquals("premium", found.get().versions().get(0).name());
        assertTrue(registry.byRunId("nope").isEmpty());
    }

    @Test
    @DisplayName("duplicate runId rejected: a rewritten history is a fabricated history")
    void duplicateRejected() {
        RunRegistry registry = new RunRegistry();
        registry.record(List.of(), metrics("run-1", "a"));

        assertThrows(IllegalArgumentException.class,
                () -> registry.record(List.of(), metrics("run-1", "a")));
    }

    @Test
    @DisplayName("byAgent filters in insertion order; all() preserves history order")
    void byAgentAndAll() {
        RunRegistry registry = new RunRegistry();
        registry.record(List.of(), metrics("run-1", "assist"));
        registry.record(List.of(), metrics("run-2", "coder"));
        registry.record(List.of(), metrics("run-3", "assist"));

        assertEquals(List.of("run-1", "run-3"),
                registry.byAgent("assist").stream().map(RunRecord::runId).toList());
        assertEquals(3, registry.all().size());
        assertEquals(List.of("run-1", "run-2", "run-3"),
                registry.all().stream().map(RunRecord::runId).toList());
    }
}

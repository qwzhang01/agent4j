package io.github.qwzhang01.agent.observability.version;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.2 acceptance for the persisted RunRegistry: every append lands as
 * one JSONL line, {@code load} rebuilds a fresh registry (time travel), and
 * the append-only discipline (duplicate runIds rejected) survives persistence.
 */
class PersistentRunRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void appendPersistsAndLoadRoundTripsTheFullRecord() throws Exception {
        Path file = tempDir.resolve("registry.jsonl");
        List<ComponentVersion> versions = List.of(
                new ComponentVersion(ComponentVersion.Kind.PROMPT, "support-system", "v3", "canary"),
                ComponentVersion.of(ComponentVersion.Kind.MODEL, "premium", "2026-09.1"),
                ComponentVersion.of(ComponentVersion.Kind.TOOL, "core", "f1"));

        try (PersistentRunRegistry persistent = new PersistentRunRegistry(file)) {
            persistent.record(versions, metrics("run-1", AgentState.Status.DONE, 300, 12_000));
            persistent.record(versions, metrics("run-2", AgentState.Status.ERROR, 999, 0));
            assertEquals(2, persistent.registry().size());
        }
        assertEquals(2, Files.readAllLines(file).size(), "one line per record");

        RunRegistry loaded = PersistentRunRegistry.load(file);
        assertEquals(2, loaded.size());

        Optional<RunRecord> first = loaded.byRunId("run-1");
        assertTrue(first.isPresent());
        assertEquals("support", first.get().agentName());
        assertEquals(3, first.get().versions().size());

        // version triple round-trips exactly: kind, name, version, channel
        ComponentVersion prompt = first.get().versions().get(0);
        assertEquals(ComponentVersion.Kind.PROMPT, prompt.kind());
        assertEquals("support-system", prompt.name());
        assertEquals("v3", prompt.version());
        assertEquals("canary", prompt.channel());
        ComponentVersion model = first.get().versions().get(1);
        assertEquals(ComponentVersion.Kind.MODEL, model.kind());
        assertEquals("premium", model.name());
        assertEquals("2026-09.1", model.version());
        assertEquals(null, model.channel(), "models carry no channel concept");

        // metrics row round-trips exactly
        RunMetrics m = first.get().metrics();
        assertEquals(AgentState.Status.DONE, m.status());
        assertEquals(300, m.durationMs());
        assertEquals(2, m.modelCallCount());
        assertEquals(1, m.toolCallCount());
        assertEquals(140, m.tokenUsage().totalTokens());
        assertEquals(20, m.tokenUsage().cachedTokens());
        assertEquals(12_000, m.costMicros());

        // the failed run kept its error text and became a failure series
        RunMetrics failed = loaded.byRunId("run-2").orElseThrow().metrics();
        assertEquals(AgentState.Status.ERROR, failed.status());
        assertEquals("provider exploded", failed.lastError());
        assertTrue(!failed.succeeded());
    }

    @Test
    void duplicateRunIdIsStillRejectedUnderPersistence() throws Exception {
        Path file = tempDir.resolve("registry.jsonl");
        try (PersistentRunRegistry persistent = new PersistentRunRegistry(file)) {
            persistent.record(List.of(), metrics("run-1", AgentState.Status.DONE, 0, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> persistent.record(List.of(), metrics("run-1", AgentState.Status.DONE, 0, 0)),
                    "append-only: duplicate runIds rejected, file or no file");
        }
        assertEquals(1, Files.readAllLines(file).size(),
                "the rejected duplicate must not have landed in the file");
    }

    @Test
    void loadOfMissingFileReturnsAnEmptyRegistry() throws Exception {
        RunRegistry loaded = PersistentRunRegistry.load(tempDir.resolve("nope.jsonl"));
        assertEquals(0, loaded.size());
        assertTrue(loaded.all().isEmpty());
    }

    @Test
    void loadedRegistryNeverSharesStateWithTheWriter() throws Exception {
        Path file = tempDir.resolve("registry.jsonl");
        try (PersistentRunRegistry persistent = new PersistentRunRegistry(file)) {
            persistent.record(List.of(), metrics("run-1", AgentState.Status.DONE, 0, 0));
        }

        RunRegistry loaded = PersistentRunRegistry.load(file);
        try (PersistentRunRegistry writer = new PersistentRunRegistry(file)) {
            writer.record(List.of(), metrics("run-2", AgentState.Status.DONE, 0, 0));
        }

        assertEquals(1, loaded.size(), "time travel: the loaded snapshot stays frozen");
        assertEquals(2, PersistentRunRegistry.load(file).size());
    }

    @Test
    void multipleRecordsForOneAgentStayOrdered() throws Exception {
        Path file = tempDir.resolve("registry.jsonl");
        try (PersistentRunRegistry persistent = new PersistentRunRegistry(file)) {
            persistent.record(List.of(), metrics("run-1", AgentState.Status.DONE, 10, 0));
            persistent.record(List.of(), metrics("run-2", AgentState.Status.DONE, 20, 0));
            persistent.record(List.of(), metrics("run-3", AgentState.Status.DONE, 30, 0));
        }

        RunRegistry loaded = PersistentRunRegistry.load(file);
        List<RunRecord> byAgent = loaded.byAgent("support");
        assertEquals(List.of("run-1", "run-2", "run-3"),
                byAgent.stream().map(RunRecord::runId).toList(),
                "oldest first, append order preserved through the file");
    }

    // ============ Helpers ============

    private static RunMetrics metrics(String runId, AgentState.Status status,
                                      long durationMs, long costMicros) {
        return new RunMetrics(
                runId, "support", status,
                status == AgentState.Status.ERROR ? "provider exploded" : null,
                durationMs,
                2, 1, 1, 0,
                new ModelResponse.TokenUsage(100, 40, 140, 20),
                costMicros);
    }
}

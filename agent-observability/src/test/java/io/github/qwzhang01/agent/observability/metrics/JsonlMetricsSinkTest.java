package io.github.qwzhang01.agent.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.2 acceptance for the JSONL persistence sink: one JSON line per
 * boundary event with a {@code type} discriminator, durable appends across
 * sink instances (append mode), and the honest dropped/written counters.
 */
class JsonlMetricsSinkTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void modelCallWritesOneTypedLineWithStructuralFields() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        try (JsonlMetricsSink sink = new JsonlMetricsSink(file)) {
            sink.onModelCall(new ModelCallMetrics("gpt-4o", 120, 100, 40, 140, 20, "stop", null));
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(1, lines.size());
        var node = mapper.readTree(lines.get(0));
        assertEquals("model_call", node.get("type").asText());
        assertEquals("gpt-4o", node.get("model").asText());
        assertEquals(120, node.get("latencyMs").asLong());
        assertEquals(100, node.get("promptTokens").asInt());
        assertEquals(40, node.get("completionTokens").asInt());
        assertEquals(140, node.get("totalTokens").asInt());
        assertEquals(20, node.get("cachedTokens").asInt());
        assertTrue(node.get("success").asBoolean());
        assertEquals("stop", node.get("finishReason").asText());
    }

    @Test
    void toolCallWritesDeniedFlagAndCappedErrorText() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        try (JsonlMetricsSink sink = new JsonlMetricsSink(file)) {
            sink.onToolCall(new ToolCallMetrics("search", 30, false, true,
                    "x".repeat(600)));
        }

        var node = mapper.readTree(Files.readString(file));
        assertEquals("tool_call", node.get("type").asText());
        assertEquals("search", node.get("tool").asText());
        assertTrue(node.get("denied").asBoolean());
        assertTrue(!node.get("success").asBoolean());
        // structural text cap: 512 chars + the ellipsis marker, never raw dumps
        assertTrue(node.get("error").asText().length() <= JsonlMetricsSink.MAX_TEXT + 3,
                "error text must be capped");
    }

    @Test
    void runRowWritesTheFullSummaryWithUsageAndCost() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        try (JsonlMetricsSink sink = new JsonlMetricsSink(file)) {
            sink.onRun(new RunMetrics(
                    "run-7", "support",
                    io.github.qwzhang01.agent.core.agent.AgentState.Status.DONE,
                    null, 300, 2, 0, 1, 0,
                    new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(100, 40, 140, 0),
                    12_000L));
        }

        var node = mapper.readTree(Files.readString(file));
        assertEquals("run", node.get("type").asText());
        assertEquals("run-7", node.get("runId").asText());
        assertEquals("support", node.get("agent").asText());
        assertEquals("DONE", node.get("status").asText());
        assertEquals(300, node.get("durationMs").asLong());
        assertEquals(2, node.get("modelCalls").asInt());
        assertEquals(1, node.get("toolCalls").asInt());
        assertEquals(140, node.get("totalTokens").asInt());
        assertEquals(12000, node.get("costMicros").asLong());
    }

    @Test
    void appendsAcrossSinkInstancesAndCountsWrittenEvents() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        try (JsonlMetricsSink first = new JsonlMetricsSink(file)) {
            first.onModelCall(new ModelCallMetrics("gpt-4o", 10, 1, 1, 2, 0, "stop", null));
            assertEquals(1, first.writtenEvents());
        }
        try (JsonlMetricsSink second = new JsonlMetricsSink(file)) {
            second.onToolCall(new ToolCallMetrics("search", 5, true, false, null));
            assertEquals(1, second.writtenEvents());
        }

        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size(), "second sink must APPEND, not truncate");
        assertEquals("model_call", mapper.readTree(lines.get(0)).get("type").asText());
        assertEquals("tool_call", mapper.readTree(lines.get(1)).get("type").asText());
    }

    @Test
    void closeIsIdempotent() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        JsonlMetricsSink sink = new JsonlMetricsSink(file);
        sink.onModelCall(new ModelCallMetrics("gpt-4o", 10, 1, 1, 2, 0, "stop", null));
        sink.close();
        sink.close();
        assertEquals(1, Files.readAllLines(file).size());
        assertEquals(0, sink.droppedEvents());
    }

    @Test
    void writingAfterCloseCountsDropsNotThrows() throws Exception {
        Path file = tempDir.resolve("metrics.jsonl");
        JsonlMetricsSink sink = new JsonlMetricsSink(file);
        sink.close();
        sink.onModelCall(new ModelCallMetrics("gpt-4o", 10, 1, 1, 2, 0, "stop", null));

        assertEquals(1, sink.droppedEvents(), "post-close write must count as dropped");
        assertEquals(0, sink.writtenEvents());
        assertTrue(Files.readAllLines(file).isEmpty());
    }
}

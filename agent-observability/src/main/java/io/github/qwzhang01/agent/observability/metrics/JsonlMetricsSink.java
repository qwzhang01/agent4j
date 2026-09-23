package io.github.qwzhang01.agent.observability.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * JSONL persistence sink : appends every boundary event as one
 * JSON line - the durable "Metrics Sink" half of 's "persisted
 * Run Registry and Metrics Sink".
 * <p>
 * Schema discipline: one line per event, {@code type} discriminator, all
 * fields structural (ids/counts/latency/model name). Error text is the
 * exception - operations must see WHY a call failed; it is capped at
 * {@link #MAX_TEXT} chars to bound line size (a stack-trace-as-label is
 * not a schema, it's a fire).
 * <p>
 * Failure discipline: a broken sink never breaks the run it observes
 * (side-channel rule); failures are counted in-memory and the sink keeps
 * accepting events (best-effort append, drop on IO error - the count is
 * the honest record of what was lost).
 */
public final class JsonlMetricsSink implements MetricsSink, AutoCloseable {

    /** Structural text cap: bounded lines, no stack-trace-as-payload. */
    static final int MAX_TEXT = 512;

    private final ObjectMapper mapper = new ObjectMapper();
    private final BufferedWriter writer;
    private long droppedEvents;
    private long writtenEvents;

    public JsonlMetricsSink(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open metrics file: " + file, e);
        }
    }

    @Override
    public synchronized void onModelCall(ModelCallMetrics metrics) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "model_call");
        node.put("ts", System.currentTimeMillis());
        node.put("model", metrics.model());
        node.put("latencyMs", metrics.latencyMs());
        node.put("promptTokens", metrics.promptTokens());
        node.put("completionTokens", metrics.completionTokens());
        node.put("totalTokens", metrics.totalTokens());
        node.put("cachedTokens", metrics.cachedTokens());
        node.put("success", metrics.success());
        if (metrics.finishReason() != null) {
            node.put("finishReason", cap(metrics.finishReason()));
        }
        if (metrics.error() != null) {
            node.put("error", cap(metrics.error()));
        }
        append(node);
    }

    @Override
    public synchronized void onToolCall(ToolCallMetrics metrics) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "tool_call");
        node.put("ts", System.currentTimeMillis());
        node.put("tool", metrics.toolName());
        node.put("latencyMs", metrics.latencyMs());
        node.put("success", metrics.success());
        node.put("denied", metrics.denied());
        if (metrics.error() != null) {
            node.put("error", cap(metrics.error()));
        }
        append(node);
    }

    @Override
    public synchronized void onRun(RunMetrics metrics) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "run");
        node.put("ts", System.currentTimeMillis());
        node.put("runId", metrics.runId());
        node.put("agent", metrics.agentName());
        node.put("status", metrics.status().name());
        node.put("durationMs", metrics.durationMs());
        node.put("modelCalls", metrics.modelCallCount());
        node.put("modelErrors", metrics.modelCallErrors());
        node.put("toolCalls", metrics.toolCallCount());
        node.put("deniedToolCalls", metrics.deniedToolCalls());
        node.put("promptTokens", metrics.tokenUsage().promptTokens());
        node.put("completionTokens", metrics.tokenUsage().completionTokens());
        node.put("totalTokens", metrics.tokenUsage().totalTokens());
        node.put("cachedTokens", metrics.tokenUsage().cachedTokens());
        node.put("costMicros", metrics.costMicros());
        if (metrics.lastError() != null) {
            node.put("lastError", cap(metrics.lastError()));
        }
        append(node);
    }

    /** Close the underlying writer; safe to call twice. */
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            droppedEvents++;
        }
    }

    /** Events lost to IO failures (the honest ledger of what the sink dropped). */
    public synchronized long droppedEvents() {
        return droppedEvents;
    }

    /** Events durably appended. */
    public synchronized long writtenEvents() {
        return writtenEvents;
    }

    private void append(ObjectNode node) {
        try {
            writer.write(node.toString());
            writer.newLine();
            writer.flush();
            writtenEvents++;
        } catch (IOException e) {
            droppedEvents++;
        }
    }

    private static String cap(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "...";
    }
}

package io.github.qwzhang01.agent.observability.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.observability.metrics.RunMetrics;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JSONL-persisted view over {@link RunRegistry} (Stage 7.2: "persisted Run
 * Registry"): every appended record also lands as one JSON line, and
 * {@link #load} rebuilds a fresh registry from a file - restart survival
 * for the "what combination served last night's bad batch" query.
 * <p>
 * Discipline unchanged from the in-memory registry: append-only, duplicate
 * runIds rejected, no updates, no deletes. The file is a projection of
 * the registry's truth, written at append time; {@link #load} returns a
 * NEW registry (the loaded one never shares state with the writer).
 * <p>
 * Failure discipline: persistence is a side channel - an IO failure at
 * append time is counted ({@link #droppedRecords}) and the in-memory
 * append still succeeds; a broken file at load time throws (the caller
 * asked for time travel and deserves a loud no, not a silent partial).
 */
public final class PersistentRunRegistry implements AutoCloseable {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RunRegistry registry = new RunRegistry();
    private final BufferedWriter writer;
    private long droppedRecords;

    public PersistentRunRegistry(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot open registry file: " + file, e);
        }
    }

    /** Append one record: in-memory registry + one JSONL line (best effort). */
    public synchronized PersistentRunRegistry add(RunRecord record) {
        registry.add(record);  // loud on duplicates, as ever
        try {
            writer.write(toJson(record).toString());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            droppedRecords++;
        }
        return this;
    }

    /** Convenience mirror of {@link RunRegistry#record}. */
    public synchronized PersistentRunRegistry record(List<ComponentVersion> versions,
                                                     RunMetrics metrics) {
        return add(RunRecord.of(versions, metrics));
    }

    /** The live in-memory view (queries land here). */
    public synchronized RunRegistry registry() {
        return registry;
    }

    /** Records lost to IO failures (the honest drop ledger). */
    public synchronized long droppedRecords() {
        return droppedRecords;
    }

    /** Close the writer; safe to call twice. */
    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            droppedRecords++;
        }
    }

    /** Rebuild a fresh in-memory registry from a JSONL file (time travel). */
    public static RunRegistry load(Path file) throws IOException {
        RunRegistry out = new RunRegistry();
        if (!Files.exists(file)) {
            return out;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            out.add(fromJson(MAPPER.readTree(line)));
        }
        return out;
    }

    private static ObjectNode toJson(RunRecord record) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("runId", record.runId());
        node.put("agentName", record.agentName());
        ArrayNode versions = node.putArray("versions");
        for (ComponentVersion v : record.versions()) {
            ObjectNode vn = versions.addObject();
            vn.put("kind", v.kind().name());
            vn.put("name", v.name());
            vn.put("version", v.version());
            if (v.channel() != null) {
                vn.put("channel", v.channel());
            }
        }
        RunMetrics m = record.metrics();
        ObjectNode mn = node.putObject("metrics");
        mn.put("runId", m.runId());
        mn.put("agentName", m.agentName());
        mn.put("status", m.status().name());
        if (m.lastError() != null) {
            mn.put("lastError", m.lastError());
        }
        mn.put("durationMs", m.durationMs());
        mn.put("modelCallCount", m.modelCallCount());
        mn.put("modelCallErrors", m.modelCallErrors());
        mn.put("toolCallCount", m.toolCallCount());
        mn.put("deniedToolCalls", m.deniedToolCalls());
        mn.put("promptTokens", m.tokenUsage().promptTokens());
        mn.put("completionTokens", m.tokenUsage().completionTokens());
        mn.put("totalTokens", m.tokenUsage().totalTokens());
        mn.put("cachedTokens", m.tokenUsage().cachedTokens());
        mn.put("costMicros", m.costMicros());
        return node;
    }

    private static RunRecord fromJson(com.fasterxml.jackson.databind.JsonNode node) {
        List<ComponentVersion> versions = new ArrayList<>();
        for (var vn : node.get("versions")) {
            versions.add(new ComponentVersion(
                    ComponentVersion.Kind.valueOf(vn.get("kind").asText()),
                    vn.get("name").asText(),
                    vn.get("version").asText(),
                    vn.has("channel") ? vn.get("channel").asText() : null));
        }
        var mn = node.get("metrics");
        var usage = new io.github.qwzhang01.agent.core.model.ModelResponse.TokenUsage(
                mn.get("promptTokens").asInt(),
                mn.get("completionTokens").asInt(),
                mn.get("totalTokens").asInt(),
                mn.get("cachedTokens").asInt());
        RunMetrics metrics = new RunMetrics(
                mn.get("runId").asText(),
                mn.get("agentName").asText(),
                io.github.qwzhang01.agent.core.agent.AgentState.Status.valueOf(mn.get("status").asText()),
                mn.has("lastError") ? mn.get("lastError").asText() : null,
                mn.get("durationMs").asLong(),
                mn.get("modelCallCount").asInt(),
                mn.get("modelCallErrors").asInt(),
                mn.get("toolCallCount").asInt(),
                mn.get("deniedToolCalls").asInt(),
                usage,
                mn.get("costMicros").asLong());
        return new RunRecord(node.get("runId").asText(), node.get("agentName").asText(),
                versions, metrics);
    }
}

package io.github.qwzhang01.agent.memory.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.memory.MemoryEntry;
import io.github.qwzhang01.agent.memory.MemoryExtractor;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * {@link MemoryExtractor} that asks a model to propose structured entries.
 * <p>
 * The host supplies extract instructions (what to look for). This class does
 * not interpret {@code subject} values — they are stored as the model returned
 * them. Invalid JSON or a failed model call yields an empty list (no invented
 * memories).
 */
public class LlmMemoryExtractor implements MemoryExtractor {

    public static final String DEFAULT_INSTRUCTIONS = """
            Extract durable memories from the conversation.
            Invent a short subject key for each item; do not use a fixed vocabulary.
            Skip chit-chat that is not worth storing.
            """;

    private static final String FORMAT_HINT = """
            Reply with JSON only, no markdown:
            {"memories":[{"type":"FACT|PREFERENCE|EVENT|EPISODE|SUMMARY","subject":"free-key","content":"text","importance":0.7,"dueAt":"2026-08-26T12:00:00Z"}]}
            Use {"memories":[]} if there is nothing to store.
            type must be one of those five names; if unsure use FACT.
            importance is optional, 0.0–1.0.
            dueAt is optional ISO-8601 (Instant or offset). Omit when there is no later follow-up time.
            This module does not interpret dueAt; hosts use it for their own scans.
            """;

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_IMPORTANCE = 0.7;

    private final ModelClient modelClient;
    private final String instructions;

    public LlmMemoryExtractor(ModelClient modelClient) {
        this(modelClient, DEFAULT_INSTRUCTIONS);
    }

    public LlmMemoryExtractor(ModelClient modelClient, String instructions) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.instructions = (instructions == null || instructions.isBlank())
                ? DEFAULT_INSTRUCTIONS
                : instructions;
    }

    public String instructions() {
        return instructions;
    }

    @Override
    public List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                     MemoryProvenance baseProvenance) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        ModelResponse response;
        try {
            response = modelClient.chat(ModelRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(instructions + "\n" + FORMAT_HINT),
                            ChatMessage.user(renderTranscript(messages))))
                    .responseFormat(ModelRequest.ResponseFormat.json())
                    .build());
        } catch (RuntimeException e) {
            log.warn("LLM extract failed: {}", e.getMessage());
            return List.of();
        }
        String raw = response == null ? null : response.content();
        return parseMemories(raw, scope, baseProvenance);
    }

    static String renderTranscript(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            if (msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            ChatRole role = msg.role();
            if (role == ChatRole.TOOL) {
                continue;
            }
            sb.append(role.name()).append(": ").append(msg.content().trim()).append('\n');
        }
        return sb.toString();
    }

    static List<MemoryEntry> parseMemories(String raw, String scope, MemoryProvenance provenance) {
        JsonNode root = readJson(raw);
        if (root == null) {
            return List.of();
        }
        JsonNode array = root.isArray() ? root : root.get("memories");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        Instant now = Instant.now();
        MemoryProvenance origin = provenance == null
                ? MemoryProvenance.modelDerived("llm-extract", null, now)
                : provenance;
        List<MemoryEntry> out = new ArrayList<>();
        for (JsonNode node : array) {
            if (node == null || !node.isObject()) {
                continue;
            }
            String content = text(node, "content");
            if (content.isBlank()) {
                continue;
            }
            String subject = text(node, "subject");
            if (subject.isBlank()) {
                subject = content.length() <= 20 ? content : content.substring(0, 20);
            }
            out.add(new MemoryEntry(
                    null,
                    scope,
                    parseType(text(node, "type")),
                    subject,
                    content,
                    parseImportance(node.get("importance")),
                    origin,
                    MemoryStatus.ACTIVE,
                    now,
                    null,
                    parseDueAt(node)
            ));
        }
        return List.copyOf(out);
    }

    private static JsonNode readJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = stripFence(raw.trim());
        try {
            return MAPPER.readTree(trimmed);
        } catch (Exception e) {
            log.warn("LLM extract returned non-JSON: {}", e.getMessage());
            return null;
        }
    }

    private static String stripFence(String raw) {
        if (!raw.startsWith("```")) {
            return raw;
        }
        int start = raw.indexOf('\n');
        if (start < 0) {
            return raw;
        }
        int end = raw.lastIndexOf("```");
        if (end <= start) {
            return raw.substring(start + 1);
        }
        return raw.substring(start + 1, end).trim();
    }

    private static MemoryType parseType(String raw) {
        if (raw.isBlank()) {
            return MemoryType.FACT;
        }
        try {
            return MemoryType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryType.FACT;
        }
    }

    private static Instant parseDueAt(JsonNode node) {
        JsonNode value = node.get("dueAt");
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText("").trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                log.warn("LLM extract dueAt ignored: {}", raw);
                return null;
            }
        }
    }

    private static double parseImportance(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return DEFAULT_IMPORTANCE;
        }
        double value = node.asDouble();
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
}

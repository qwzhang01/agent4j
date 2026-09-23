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
import io.github.qwzhang01.agent.memory.MemoryLifecycle;
import io.github.qwzhang01.agent.memory.MemoryPolicy;
import io.github.qwzhang01.agent.memory.MemoryProvenance;
import io.github.qwzhang01.agent.memory.MemoryStatus;
import io.github.qwzhang01.agent.memory.MemoryStore;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * {@link MemoryExtractor} that asks a model to propose structured entries.
 * <p>
 * The host supplies extract instructions (what to look for). This class does
 * not interpret {@code subject} values — they are stored as the model returned
 * them. Invalid JSON or a failed model call yields an empty list (no invented
 * memories).
 * <p>
 * <b>Async + sampling</b>: use {@link #extractAsync} to run extraction off the
 * calling thread with probabilistic down-sampling. The sample decision is
 * {@code Math.floorMod(sessionHash ^ seed, 100) < sampleRate} where
 * {@code sessionHash} is {@code sessionId.hashCode}. This formula is
 * deterministic across JVM restarts for the same input pair, so replaying a
 * session always makes the same sampling decision.
 */
public class LlmMemoryExtractor implements MemoryExtractor {

    public static final String DEFAULT_INSTRUCTIONS = """
            Extract durable memories from the conversation.
            Invent a short subject key for each item; do not use a fixed vocabulary.
            Skip chit-chat that is not worth storing.
            """;

    private static final String FORMAT_HINT = """
            Reply with JSON only, no markdown:
            {"memories":[{"type":"FACT|PREFERENCE|EVENT|EPISODE|SUMMARY","subject":"free-key","content":"text","importance":0.7,"lifecycle":"EVOLVE|CONFLICT","dueAt":"2026-08-26T12:00:00Z","validFrom":"2026-08-20T00:00:00Z"}]}
            Use {"memories":[]} if there is nothing to store.
            type must be one of those five names; if unsure use FACT.
            importance is optional, 0.0–1.0.
            If an "Existing subjects" list is provided, prefer it:
              set "subject" to an EXISTING one when the memory updates that topic,
              else invent a new short key.
            lifecycle is optional and only set when an existing entry about the subject is being replaced:
              EVOLVE   = the old info was once true but changed (moved cities, new job, quit smoking, now prefers)
              CONFLICT = the old info was wrong from the start ("you remembered it wrong", "I never had/said that")
            Omit lifecycle when no existing entry about the subject is being replaced.
            validFrom is optional ISO-8601 (Instant or offset) business time: when the fact
            became true in the world (e.g. "I moved last week" -> last week's date). Omit when
            the conversation gives no such time; then it defaults to the recording time.
            dueAt is optional ISO-8601 (Instant or offset). Omit when there is no later follow-up time.
            This module does not interpret dueAt; hosts use it for their own scans.
            """;

    private static final Logger log = LoggerFactory.getLogger(LlmMemoryExtractor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double DEFAULT_IMPORTANCE = 0.7;

    /** Default rate: always extract (100 = 100%). Preserves pre-sampling behaviour. */
    private static final int DEFAULT_SAMPLE_RATE = 100;

    /**
     * Shared fallback pool used only when the host does not inject its own {@link Executor}.
     * <p>
     * Deliberately <em>not</em> {@link java.util.concurrent.ForkJoinPool#commonPool}: that
     * pool is shared with unrelated parallel streams throughout the JVM, and an LLM call can
     * block a common-pool worker for seconds — starving unrelated {@code parallelStream}
     * work elsewhere in the host application. This pool is small, bounded, and uses daemon
     * threads so it never blocks JVM shutdown. Hosts running non-trivial extraction volume
     * should inject a purpose-sized {@link Executor} via the full constructor instead of
     * relying on this fallback.
     */
    private static final Executor DEFAULT_EXECUTOR = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            runnable -> {
                Thread t = new Thread(runnable, "agent4j-memory-extract");
                t.setDaemon(true);
                return t;
            });

    private final ModelClient modelClient;
    private final String instructions;
    /** 0 = never, 100 = always, 1–99 = probabilistic. */
    private final int sampleRate;
    /** XOR salt for the sampling hash; same seed → same decision for same sessionId. */
    private final long seed;
    /** {@code null} = use {@link #DEFAULT_EXECUTOR} (a dedicated bounded pool, not commonPool). */
    private final Executor executor;

    /** Backward-compatible: synchronous, always extracts. */
    public LlmMemoryExtractor(ModelClient modelClient) {
        this(modelClient, DEFAULT_INSTRUCTIONS, DEFAULT_SAMPLE_RATE, 0L, null);
    }

    /** Backward-compatible: synchronous, always extracts, custom instructions. */
    public LlmMemoryExtractor(ModelClient modelClient, String instructions) {
        this(modelClient, instructions, DEFAULT_SAMPLE_RATE, 0L, null);
    }

    /**
     * With sampling; uses the shared {@link #DEFAULT_EXECUTOR} for async extraction.
     *
     * @param sampleRate 0–100; percentage of sessions that trigger extraction
     * @param seed XOR salt for the sampling hash (e.g. per-deployment constant)
     */
    public LlmMemoryExtractor(ModelClient modelClient, String instructions,
                               int sampleRate, long seed) {
        this(modelClient, instructions, sampleRate, seed, null);
    }

    /**
     * Full constructor.
     *
     * @param executor thread pool for async extraction; {@code null} falls back to a small
     *                 dedicated daemon pool shared by all {@code LlmMemoryExtractor} instances
     *                 (see {@link #DEFAULT_EXECUTOR}). Production hosts with meaningful
     *                 extraction volume should inject their own sized {@link Executor}.
     */
    public LlmMemoryExtractor(ModelClient modelClient, String instructions,
                               int sampleRate, long seed, Executor executor) {
        if (sampleRate < 0 || sampleRate > 100) {
            throw new IllegalArgumentException(
                    "sampleRate must be in [0, 100], got: " + sampleRate);
        }
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
        this.instructions = (instructions == null || instructions.isBlank())
                ? DEFAULT_INSTRUCTIONS
                : instructions;
        this.sampleRate = sampleRate;
        this.seed = seed;
        this.executor = executor;
    }

    public String instructions() {
        return instructions;
    }

    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                     MemoryProvenance baseProvenance) {
        return extract(messages, scope, baseProvenance, List.of());
    }

    /**
     * Reconciliation-aware extraction (memory route step 2): recalled old
     * entries are rendered as an "Existing subjects" block in the system prompt
     * so the model can pick an existing key instead of inventing a drifting
     * one, and judge lifecycle against what the old entries actually said.
     */
    @Override
    public List<MemoryEntry> extract(List<ChatMessage> messages, String scope,
                                     MemoryProvenance baseProvenance,
                                     List<MemoryEntry> evidence) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        ModelResponse response;
        try {
            response = modelClient.chat(ModelRequest.builder()
                    .messages(List.of(
                            ChatMessage.system(instructions + "\n" + FORMAT_HINT
                                    + renderExistingSubjects(evidence)),
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

    /**
     * Render the recalled old entries as the "Existing subjects" prompt block.
     * Empty evidence renders nothing — identical to the pre-reconciliation prompt.
     */
    static String renderExistingSubjects(List<MemoryEntry> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nExisting subjects:\n");
        for (MemoryEntry e : evidence) {
            sb.append("- ").append(e.subject()).append(": ").append(e.content()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Runs the full extract-and-store pipeline asynchronously.
     * <p>
     * The call returns immediately with a {@link CompletableFuture}.
     * If the sampling check decides to skip this session, the future
     * completes at once with {@code 0} (no model call is made).
     * Any runtime exception in the extraction pipeline is caught:
     * the future always completes normally (never exceptionally).
     *
     * @param sessionId stable identifier for the session or conversation;
     *                  used to compute the sampling hash; {@code null} → treated as empty string
     * @return future of the number of entries actually stored, or {@code 0} if skipped / failed
     */
    public CompletableFuture<Integer> extractAsync(
            List<ChatMessage> messages, String scope,
            MemoryProvenance provenance, MemoryPolicy policy,
            MemoryStore store, String sessionId) {
        if (!shouldSample(sessionId)) {
            return CompletableFuture.completedFuture(0);
        }
        Supplier<Integer> task = () -> extractAndStore(messages, scope, provenance, policy, store);
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(
                task, executor != null ? executor : DEFAULT_EXECUTOR);
        return future.exceptionally(e -> {
            log.warn("extractAsync failed for session '{}': {}", sessionId, e.getMessage());
            return 0;
        });
    }

    /**
     * Sampling decision: {@code Math.floorMod(sessionHash ^ seed, 100) < sampleRate}.
     * Deterministic for identical inputs across JVM restarts.
     */
    private boolean shouldSample(String sessionId) {
        if (sampleRate <= 0) return false;
        if (sampleRate >= 100) return true;
        long hash = sessionId == null ? 0L : (long) sessionId.hashCode();
        return Math.floorMod(hash ^ seed, 100L) < sampleRate;
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
                    parseDueAt(node),
                    parseLifecycle(node),
                    null,
                    parseValidFrom(node),
                    null,
                    null
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
        return parseInstant(raw, "dueAt");
    }

    /**
     * Parses the optional {@code validFrom} field (business-axis start time).
     * Same tolerance as dueAt: ISO-8601 Instant or offset; anything else is
     * ignored (null) rather than failing the whole entry.
     */
    private static Instant parseValidFrom(JsonNode node) {
        JsonNode value = node.get("validFrom");
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText("").trim();
        if (raw.isBlank()) {
            return null;
        }
        return parseInstant(raw, "validFrom");
    }

    /**
     * Shared ISO-8601 parsing with offset fallback; malformed values are
     * logged and dropped (null), never thrown — a bad timestamp must not
     * discard an otherwise good memory.
     */
    private static Instant parseInstant(String raw, String field) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                log.warn("LLM extract {} ignored: {}", field, raw);
                return null;
            }
        }
    }

    /**
     * Parses the optional {@code lifecycle} field. Only the exact enum names
     * EVOLVE / CONFLICT are accepted; anything else (including a missing field)
     * yields {@code null} = not judged, which the write path treats as CONFLICT.
     */
    private static MemoryLifecycle parseLifecycle(JsonNode node) {
        JsonNode value = node.get("lifecycle");
        if (value == null || value.isNull()) {
            return null;
        }
        String raw = value.asText("").trim();
        if (raw.isBlank()) {
            return null;
        }
        try {
            return MemoryLifecycle.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("LLM extract lifecycle ignored: {}", raw);
            return null;
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

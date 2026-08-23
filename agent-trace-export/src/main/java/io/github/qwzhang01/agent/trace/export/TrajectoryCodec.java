package io.github.qwzhang01.agent.trace.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.trace.trajectory.DoneReason;
import io.github.qwzhang01.agent.trace.trajectory.StepAction;
import io.github.qwzhang01.agent.trace.trajectory.ToolObservation;
import io.github.qwzhang01.agent.trace.trajectory.Trajectory;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryMetadata;
import io.github.qwzhang01.agent.trace.trajectory.TrajectoryStep;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trajectory &lt;-&gt; JSON tree, the SINGLE implementation point of the v1
 * export contract (Stage 14 D8).
 * <p>
 * Hand-built trees instead of Jackson annotations on the model records:
 * the contract must be explicit down to every field name (snake_case, the
 * training-ecosystem convention), never inferred from a naming strategy.
 * The golden field-name snapshot test locks this; changing a field name is
 * an api_version bump, not an edit (D2: training data is a long-lived asset).
 * <p>
 * v1 contract boundaries (documented, deliberate):
 * <ul>
 *   <li>multimodal {@code parts} are NOT exported (text trajectories only);
 *       round-trips of text conversations are lossless</li>
 *   <li>{@code done_reason} is written at the top level for consumer
 *       convenience and DERIVED from steps on load (never trusted blindly)</li>
 *   <li>null fields and empty collections are omitted; loaders default them</li>
 * </ul>
 */
public final class TrajectoryCodec {

    /** Contract envelope: version of the on-disk schema (D8, aligned with Stage 13 discipline). */
    public static final String API_VERSION = "v1";
    public static final String KIND = "Trajectory";

    private final ObjectMapper mapper = new ObjectMapper();

    public ObjectNode toJson(Trajectory trajectory) {
        ObjectNode node = mapper.createObjectNode();
        node.put("api_version", API_VERSION);
        node.put("kind", KIND);
        node.put("trajectory_id", trajectory.trajectoryId());
        node.put("run_id", trajectory.runId());
        node.set("metadata", metadataToJson(trajectory.metadata()));
        node.put("status", trajectory.status().name());
        DoneReason reason = trajectory.doneReason();
        if (reason != null) {
            node.put("done_reason", reason.name());
        }
        if (trajectory.reward() != null) {
            node.put("reward", trajectory.reward());
        }
        setIfNotBlank(node, "reward_source", trajectory.rewardSource());

        ArrayNode messages = node.putArray("messages");
        for (ChatMessage message : trajectory.messages()) {
            messages.add(messageToJson(message));
        }
        ArrayNode steps = node.putArray("steps");
        for (TrajectoryStep step : trajectory.steps()) {
            steps.add(stepToJson(step));
        }
        return node;
    }

    /** Parse a JSON string into a tree (writer/loader plumbing). */
    public JsonNode toJsonNode(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage(), e);
        }
    }

    public Trajectory fromJson(JsonNode node) {
        requireField(node, "api_version");
        if (!API_VERSION.equals(node.get("api_version").asText())) {
            throw new IllegalArgumentException("unsupported api_version: "
                    + node.get("api_version").asText());
        }
        if (node.has("kind") && !KIND.equals(node.get("kind").asText())) {
            throw new IllegalArgumentException("unexpected kind: " + node.get("kind").asText());
        }
        String trajectoryId = text(node, "trajectory_id");
        String runId = text(node, "run_id");
        TrajectoryMetadata metadata = node.has("metadata")
                ? metadataFromJson(node.get("metadata"))
                : new TrajectoryMetadata(null, null, List.of(), null, null, null, 0,
                        null, null, Map.of());
        AgentState.Status status = AgentState.Status.valueOf(text(node, "status"));
        List<TrajectoryStep> steps = new ArrayList<>();
        for (JsonNode stepNode : node.path("steps")) {
            steps.add(stepFromJson(stepNode));
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode messageNode : node.path("messages")) {
            messages.add(messageFromJson(messageNode));
        }
        Double reward = node.hasNonNull("reward") ? node.get("reward").asDouble() : null;
        String rewardSource = node.has("reward_source") ? node.get("reward_source").asText() : null;
        return new Trajectory(trajectoryId, runId, metadata, status, steps, messages, reward, rewardSource);
    }

    // ============ metadata ============

    private ObjectNode metadataToJson(TrajectoryMetadata metadata) {
        ObjectNode node = mapper.createObjectNode();
        setIfNotBlank(node, "agent_name", metadata.agentName());
        setIfNotBlank(node, "prompt_sha256", metadata.promptSha256());
        if (!metadata.tools().isEmpty()) {
            ArrayNode tools = node.putArray("tools");
            metadata.tools().forEach(tools::add);
        }
        if (metadata.maxSteps() != null) {
            node.put("max_steps", metadata.maxSteps());
        }
        setIfNotBlank(node, "started_at", metadata.startedAt() == null ? null : metadata.startedAt().toString());
        setIfNotBlank(node, "finished_at", metadata.finishedAt() == null ? null : metadata.finishedAt().toString());
        node.put("duration_ms", metadata.durationMs());
        node.set("token_usage", usageToJson(metadata.tokenUsage()));
        setIfNotBlank(node, "last_error", metadata.lastError());
        if (!metadata.custom().isEmpty()) {
            ObjectNode custom = node.putObject("custom");
            metadata.custom().forEach(custom::put);
        }
        return node;
    }

    private TrajectoryMetadata metadataFromJson(JsonNode node) {
        List<String> tools = new ArrayList<>();
        node.path("tools").forEach(t -> tools.add(t.asText()));
        Map<String, String> custom = new LinkedHashMap<>();
        JsonNode customNode = node.path("custom");
        customNode.fields().forEachRemaining(e -> custom.put(e.getKey(), e.getValue().asText()));
        return new TrajectoryMetadata(
                textOrNull(node, "agent_name"),
                textOrNull(node, "prompt_sha256"),
                tools,
                node.has("max_steps") ? node.get("max_steps").asInt() : null,
                node.has("started_at") ? Instant.parse(node.get("started_at").asText()) : null,
                node.has("finished_at") ? Instant.parse(node.get("finished_at").asText()) : null,
                node.path("duration_ms").asLong(0),
                usageFromJson(node.path("token_usage")),
                textOrNull(node, "last_error"),
                custom);
    }

    // ============ step / action / observation ============

    private ObjectNode stepToJson(TrajectoryStep step) {
        ObjectNode node = mapper.createObjectNode();
        node.put("index", step.index());
        ArrayNode state = node.putArray("state");
        step.state().forEach(m -> state.add(messageToJson(m)));
        node.set("action", actionToJson(step.action()));
        if (!step.observations().isEmpty()) {
            ArrayNode observations = node.putArray("observations");
            step.observations().forEach(o -> observations.add(observationToJson(o)));
        }
        if (step.reward() != null) {
            node.put("reward", step.reward());
        }
        node.put("done", step.done());
        if (step.doneReason() != null) {
            node.put("done_reason", step.doneReason().name());
        }
        return node;
    }

    private TrajectoryStep stepFromJson(JsonNode node) {
        List<ChatMessage> state = new ArrayList<>();
        node.path("state").forEach(m -> state.add(messageFromJson(m)));
        List<ToolObservation> observations = new ArrayList<>();
        node.path("observations").forEach(o -> observations.add(observationFromJson(o)));
        Double reward = node.hasNonNull("reward") ? node.get("reward").asDouble() : null;
        DoneReason doneReason = node.has("done_reason")
                ? DoneReason.valueOf(node.get("done_reason").asText())
                : null;
        return new TrajectoryStep(node.path("index").asInt(), state,
                actionFromJson(node.path("action")), observations, reward,
                node.path("done").asBoolean(false), doneReason);
    }

    private ObjectNode actionToJson(StepAction action) {
        ObjectNode node = mapper.createObjectNode();
        setIfNotBlank(node, "content", action.content());
        if (action.hasToolCalls()) {
            ArrayNode calls = node.putArray("tool_calls");
            action.toolCalls().forEach(c -> calls.add(toolCallToJson(c)));
        }
        node.put("finish_reason", action.finishReason());
        if (action.usage() != null) {
            node.set("usage", usageToJson(action.usage()));
        }
        node.put("duration_ms", action.durationMs());
        return node;
    }

    private StepAction actionFromJson(JsonNode node) {
        List<ToolCall> calls = new ArrayList<>();
        node.path("tool_calls").forEach(c -> calls.add(toolCallFromJson(c)));
        return new StepAction(
                textOrNull(node, "content"),
                calls.isEmpty() ? null : calls,
                text(node, "finish_reason"),
                node.has("usage") ? usageFromJson(node.get("usage")) : null,
                node.path("duration_ms").asLong(0));
    }

    private ObjectNode observationToJson(ToolObservation observation) {
        ObjectNode node = mapper.createObjectNode();
        node.put("tool_call_id", observation.toolCallId());
        setIfNotBlank(node, "name", observation.name());
        setIfNotBlank(node, "content", observation.content());
        node.put("success", observation.success());
        node.put("duration_ms", observation.durationMs());
        return node;
    }

    private ToolObservation observationFromJson(JsonNode node) {
        return new ToolObservation(
                text(node, "tool_call_id"),
                textOrNull(node, "name"),
                textOrNull(node, "content"),
                node.path("success").asBoolean(true),
                node.path("duration_ms").asLong(0));
    }

    // ============ shared shapes ============

    private ObjectNode usageToJson(ModelResponse.TokenUsage usage) {
        ObjectNode node = mapper.createObjectNode();
        if (usage == null) {
            return node;
        }
        node.put("prompt_tokens", usage.promptTokens());
        node.put("completion_tokens", usage.completionTokens());
        node.put("total_tokens", usage.totalTokens());
        return node;
    }

    private ModelResponse.TokenUsage usageFromJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isEmpty()) {
            return null;
        }
        return new ModelResponse.TokenUsage(
                node.path("prompt_tokens").asInt(0),
                node.path("completion_tokens").asInt(0),
                node.path("total_tokens").asInt(0));
    }

    private ObjectNode toolCallToJson(ToolCall call) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", call.id());
        node.put("name", call.name());
        node.set("arguments", call.arguments() == null ? mapper.createObjectNode() : call.arguments());
        return node;
    }

    private ToolCall toolCallFromJson(JsonNode node) {
        JsonNode arguments = node.path("arguments");
        return ToolCall.of(text(node, "id"), text(node, "name"), arguments.toString());
    }

    private ObjectNode messageToJson(ChatMessage message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().name());
        setIfNotBlank(node, "content", message.content());
        if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            ArrayNode calls = node.putArray("tool_calls");
            message.toolCalls().forEach(c -> calls.add(toolCallToJson(c)));
        }
        setIfNotBlank(node, "tool_call_id", message.toolCallId());
        setIfNotBlank(node, "name", message.name());
        return node;
    }

    private ChatMessage messageFromJson(JsonNode node) {
        List<ToolCall> calls = new ArrayList<>();
        node.path("tool_calls").forEach(c -> calls.add(toolCallFromJson(c)));
        // parts are not part of the v1 contract (text trajectories); null keeps
        // the compact constructor's normalization consistent with recording
        return new ChatMessage(
                ChatRole.valueOf(text(node, "role")),
                textOrNull(node, "content"),
                null,
                calls.isEmpty() ? null : calls,
                textOrNull(node, "tool_call_id"),
                textOrNull(node, "name"));
    }

    // ============ shared shapes (public for feedback-side reuse) ============

    /** Fresh object node on this codec's mapper (feedback sidecar / DPO rows). */
    public ObjectNode createObjectNode() {
        return mapper.createObjectNode();
    }

    /** Serialize a message list exactly as it appears inside trajectories (one implementation point). */
    public ArrayNode messagesToJson(List<ChatMessage> messages) {
        ArrayNode array = mapper.createArrayNode();
        for (ChatMessage message : messages) {
            array.add(messageToJson(message));
        }
        return array;
    }

    // ============ small helpers ============

    private static void setIfNotBlank(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("missing required field '" + field + "'");
        }
        return value.asText();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void requireField(JsonNode node, String field) {
        if (!node.has(field)) {
            throw new IllegalArgumentException("missing required field '" + field + "'");
        }
    }
}

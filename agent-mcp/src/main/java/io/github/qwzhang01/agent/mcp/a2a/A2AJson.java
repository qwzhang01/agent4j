package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire codec between our A2A data model and the spec's JSON dialect.
 * <p>
 * Both sides of the protocol live here: the HTTP client builds requests and
 * parses responses with these methods, the HTTP server parses requests and
 * builds responses with the SAME methods -- one dialect, two mouths. This is
 * the a2a sibling of the JSON-RPC shapes in the mcp package.
 * <p>
 * Where our model is richer than the spec (A2ATask.taskType / sender /
 * deadline have no spec counterpart), those fields ride
 * {@code params.metadata} on the wire instead of inventing non-spec fields --
 * a spec peer that ignores metadata still round-trips the parts it does
 * understand. Where the spec is richer than our v1 (non-text parts, skills
 * with descriptions/tags), parsing skips what we cannot model instead of
 * rejecting the whole message.
 * <p>
 * v1 subset, honestly: text parts only, no message/stream, no push
 * notifications, no task continuation.
 */
public final class A2AJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private A2AJson() {
    }

    /** Shared mapper (single config point if policies are ever needed). */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    // AgentCard (/.well-known/agent.json)

    /**
     * Card to spec JSON. {@code urlOverride} (nullable) wins over the card's
     * own url -- the server uses it to advertise its actual bound address
     * when the card was built without a real http(s) url.
     */
    public static ObjectNode cardJson(AgentCard card, String urlOverride) {
        String url = urlOverride != null ? urlOverride : card.url();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("name", card.name());
        if (card.description() != null) {
            node.put("description", card.description());
        }
        if (url != null) {
            node.put("url", url);
        }
        node.put("version", card.version() == null ? "1.0" : card.version());
        ObjectNode caps = node.putObject("capabilities");
        A2ACapabilities c = card.capabilities();
        caps.put("streaming", c.streaming());
        caps.put("pushNotifications", c.pushNotifications());
        caps.put("stateTransitionHistory", c.stateTransitionHistory());
        node.put("preferredTransport", "JSONRPC");
        ArrayNode skills = node.putArray("skills");
        for (String s : card.skills()) {
            ObjectNode skill = skills.addObject();
            skill.put("id", s);
            skill.put("name", s);
            skill.put("description", "capability: " + s);
        }
        return node;
    }

    /**
     * Spec JSON to card. {@code fallbackUrl} fills url when the card omits it
     * (defensive: the well-known fetch already knows where it came from).
     * Skills are flattened to their name (fallback id): our routing matches
     * on plain strings. Unknown fields are ignored.
     */
    public static AgentCard cardFrom(JsonNode node, String fallbackUrl) {
        String url = textOrNull(node, "url");
        if (url == null) {
            url = fallbackUrl;
        }
        List<String> skills = new ArrayList<>();
        for (JsonNode skill : node.path("skills")) {
            String label = textOrNull(skill, "name");
            if (label == null) {
                label = textOrNull(skill, "id");
            }
            if (label != null && !label.isBlank()) {
                skills.add(label);
            }
        }
        JsonNode caps = node.path("capabilities");
        A2ACapabilities capabilities = new A2ACapabilities(
                caps.path("streaming").asBoolean(false),
                caps.path("pushNotifications").asBoolean(false),
                caps.path("stateTransitionHistory").asBoolean(false));
        // For a discovered card the endpoint IS the reachable url.
        return new AgentCard(
                textOrDefault(node, "name", "unnamed"),
                textOrNull(node, "description"),
                List.copyOf(skills),
                url,
                textOrDefault(node, "version", "1.0"),
                url,
                capabilities);
    }

    // Task (server-side shape)

    /**
     * Task to spec JSON: {@code id / contextId / status{state,message} /
     * artifacts[{artifactId,name,parts[{kind,text}]}]}.
     */
    public static ObjectNode taskJson(String taskId, String contextId, A2ATaskStatus status,
                                      String statusMessage, List<A2AArtifact> artifacts) {
        ObjectNode task = MAPPER.createObjectNode();
        task.put("id", taskId);
        if (contextId != null) {
            task.put("contextId", contextId);
        }
        ObjectNode statusNode = task.putObject("status");
        statusNode.put("state", status.label());
        if (statusMessage != null) {
            statusNode.put("message", statusMessage);
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            ArrayNode array = task.putArray("artifacts");
            for (A2AArtifact artifact : artifacts) {
                ObjectNode artifactNode = array.addObject();
                artifactNode.put("artifactId", artifact.artifactId());
                if (artifact.name() != null) {
                    artifactNode.put("name", artifact.name());
                }
                ObjectNode part = artifactNode.putArray("parts").addObject();
                part.put("kind", "text");
                part.put("text", artifact.text());
            }
        }
        return task;
    }

    /**
     * Spec JSON to task. Our extension fields (recipient/taskType/sender/
     * deadline) have no wire counterpart here and stay null -- the recipient
     * is whoever you talked to. Payload mirrors the client convention:
     * {@code {"output": firstArtifactText}} when artifacts exist.
     */
    public static A2ATask taskFrom(JsonNode node) {
        String taskId = textOrDefault(node, "id", textOrDefault(node, "taskId", "unknown"));
        A2ATaskStatus status = A2ATaskStatus.fromLabel(textOrNull(node.path("status"), "state"));
        List<A2AArtifact> artifacts = new ArrayList<>();
        for (JsonNode artifact : node.path("artifacts")) {
            String text = firstTextPart(artifact.path("parts"));
            if (text != null) {
                artifacts.add(new A2AArtifact(
                        textOrDefault(artifact, "artifactId", "artifact"),
                        textOrNull(artifact, "name"),
                        text));
            }
        }
        JsonNode payload = null;
        if (!artifacts.isEmpty()) {
            payload = MAPPER.createObjectNode().put("output", artifacts.get(0).text());
        }
        return new A2ATask(taskId, "remote", null, payload, null, null,
                textOrNull(node, "contextId"), status, artifacts);
    }

    // JSON-RPC requests (client -> server)

    /**
     * Build a {@code message/send} request from our task. The prompt rides a
     * user message text part; a new task carries no {@code message.taskId}
     * (in the spec that field means "continue an existing task", and the
     * SERVER assigns task ids). Our taskType/sender/deadline ride
     * {@code params.metadata}.
     */
    public static ObjectNode messageSendRequest(long rpcId, A2ATask task) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", rpcId);
        request.put("method", "message/send");
        ObjectNode params = request.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("role", "user");
        ObjectNode part = message.putArray("parts").addObject();
        part.put("kind", "text");
        part.put("text", promptOf(task));
        if (task.contextId() != null) {
            message.put("contextId", task.contextId());
        }
        if (task.taskType() != null || task.sender() != null || task.deadline() != null) {
            ObjectNode metadata = params.putObject("metadata");
            if (task.taskType() != null) {
                metadata.put("taskType", task.taskType());
            }
            if (task.sender() != null) {
                metadata.put("sender", task.sender());
            }
            if (task.deadline() != null) {
                metadata.put("deadline", task.deadline());
            }
        }
        return request;
    }

    /**
     * Continue an existing task: {@code message/send} with {@code message.taskId}.
     * Spec: this field means "this message belongs to that task", not a new one.
     */
    public static ObjectNode messageContinueRequest(long rpcId, String remoteTaskId, String text) {
        ObjectNode request = mapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", rpcId);
        request.put("method", "message/send");
        ObjectNode params = request.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("messageId", UUID.randomUUID().toString());
        message.put("role", "user");
        message.put("taskId", remoteTaskId);
        ObjectNode part = message.putArray("parts").addObject();
        part.put("kind", "text");
        part.put("text", text == null ? "" : text);
        return request;
    }

    /** Same body as {@link #messageSendRequest} but method {@code message/stream}. */
    public static ObjectNode messageStreamRequest(long rpcId, A2ATask task) {
        ObjectNode request = messageSendRequest(rpcId, task);
        request.put("method", "message/stream");
        return request;
    }

    /** {@code tasks/pushNotification/set} — attach a webhook to a server task id. */
    public static ObjectNode pushSetRequest(long rpcId, String remoteTaskId, String webhookUrl) {
        ObjectNode request = mapper().createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", rpcId);
        request.put("method", "tasks/pushNotification/set");
        ObjectNode params = request.putObject("params");
        params.put("id", remoteTaskId);
        params.putObject("pushNotificationConfig").put("url", webhookUrl);
        return request;
    }

    /** Build a {@code tasks/get} request for a server-assigned task id. */
    public static ObjectNode tasksGetRequest(long rpcId, String taskId) {
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", rpcId);
        request.put("method", "tasks/get");
        request.putObject("params").put("id", taskId);
        return request;
    }

    // JSON-RPC envelopes (server -> client)

    /** Success envelope. {@code id} is echoed from the request. */
    public static ObjectNode rpcResult(JsonNode id, JsonNode result) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        node.set("id", id);
        node.set("result", result);
        return node;
    }

    /** Error envelope. A null/missing id (parse errors) is simply omitted. */
    public static ObjectNode rpcError(JsonNode id, int code, String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("jsonrpc", "2.0");
        if (id != null && !id.isNull()) {
            node.set("id", id);
        }
        ObjectNode error = node.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return node;
    }

    /**
     * Extract the first text part of a message/artifact parts array. v1
     * understands text parts only; file/data parts are skipped, not fatal.
     * Returns null when no text part exists.
     */
    public static String firstTextPart(JsonNode parts) {
        for (JsonNode part : parts) {
            if (part.path("text").isTextual()) {
                return part.get("text").asText();
            }
        }
        return null;
    }

    /**
     * The prompt an agent should run for a task: payload's "prompt" field if
     * textual, else the whole payload as JSON, else empty (the in-process
     * client's convention, now shared by the wire).
     */
    public static String promptOf(A2ATask task) {
        JsonNode payload = task.payload();
        if (payload != null && payload.path("prompt").isTextual()) {
            return payload.get("prompt").asText();
        }
        return payload == null ? "" : payload.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        String value = textOrNull(node, field);
        return value == null ? fallback : value;
    }
}

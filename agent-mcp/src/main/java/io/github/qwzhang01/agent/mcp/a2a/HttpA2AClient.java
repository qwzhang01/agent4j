package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A2A client over real HTTP (JDK HttpClient, zero new dependencies).
 * <p>
 * This is the mouth that reaches outside the JVM: it speaks the spec's
 * JSON-RPC dialect to any A2A endpoint -- {@code message/send} for task
 * delegation, {@code tasks/get} for status, {@code /.well-known/agent.json}
 * for discovery. Same interface as {@link InProcessA2AClient}; what changed
 * is only the transport, exactly the swap Stage 11's D6 promised.
 * <p>
 * Identity note: in the spec the SERVER assigns task ids. This client keeps
 * a local-to-remote id map per instance, so {@link #getTaskStatus} answers
 * for the LOCAL taskId you sent through THIS client (null = never sent
 * here); the remote id rides the sendTask result as {@code "taskId"} if a
 * host needs it raw. A task sent through one client instance cannot be
 * polled through another -- v1 has no shared task registry, by design.
 * <p>
 * Result convention (same as the in-process client): a completed task
 * returns {@code {"output": text, "taskId": remoteId, "contextId": ...}};
 * a task the peer pauses returns {@code {"status": "input-required", ...}}
 * as data; a task that failed / was rejected / canceled throws
 * {@link IllegalStateException} (the task ran; the failure is the answer).
 * Transport and protocol-level failures (connect refused, non-200, JSON-RPC
 * error envelope) throw {@link A2AHttpException} instead -- the call itself
 * broke, no task answer exists.
 * <p>
 * v1 honest boundaries: no {@code message/stream} (SSE), no push
 * notifications, no task continuation, and {@link #sendMessage} throws
 * {@link UnsupportedOperationException} because mapping a fire-and-forget
 * message onto "create a task" would be a semantic lie.
 */
public class HttpA2AClient implements A2AClient {

    private static final Logger log = LoggerFactory.getLogger(HttpA2AClient.class);

    /** Spec reserves -32001 for "task not found" (server error range). */
    static final int TASK_NOT_FOUND = -32001;

    private final String baseUrl;
    private final HttpClient http;
    private final Duration timeout;
    private final AtomicLong rpcIds = new AtomicLong(1);
    private final Map<String, String> localToRemoteTask = new ConcurrentHashMap<>();

    /** Default timeout 120s: server-side agents run synchronously and can be slow. */
    public HttpA2AClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(120));
    }

    public HttpA2AClient(String baseUrl, Duration timeout) {
        this(baseUrl, timeout, HttpClient.newHttpClient());
    }

    public HttpA2AClient(String baseUrl, Duration timeout, HttpClient http) {
        String base = Objects.requireNonNull(baseUrl, "baseUrl must not be null").strip();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw new IllegalArgumentException("baseUrl must start with http:// or https://: " + baseUrl);
        }
        this.baseUrl = base;
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
        this.http = Objects.requireNonNull(http, "http must not be null");
    }

    // ============ Discovery ============

    /**
     * Fetch the peer's agent card from {@code /.well-known/agent.json}.
     * One endpoint = one card (the spec's rule), so this returns a
     * one-element list.
     */
    @Override
    public List<AgentCard> discoverAgents() {
        String url = baseUrl + "/.well-known/agent.json";
        String body = getJson(url);
        try {
            return List.of(A2AJson.cardFrom(A2AJson.mapper().readTree(body), baseUrl + "/"));
        } catch (IOException e) {
            throw new A2AHttpException(A2AHttpException.TRANSPORT,
                    "agent card at " + url + " is not valid JSON: " + e.getMessage(), e);
        }
    }

    // ============ Task delegation ============

    /**
     * Delegate a task via {@code message/send}. The task's recipient field
     * is ignored over HTTP: the URL you constructed this client with IS the
     * recipient.
     */
    @Override
    public JsonNode sendTask(A2ATask task) {
        Objects.requireNonNull(task, "task must not be null");
        ObjectNode request = A2AJson.messageSendRequest(rpcIds.getAndIncrement(), task);
        JsonNode resultTask = rpc(request).path("result");
        if (!resultTask.isObject()) {
            throw new A2AHttpException(A2AHttpException.TRANSPORT,
                    "A2A endpoint " + baseUrl + " returned no task object", null);
        }
        A2ATask remote = A2AJson.taskFrom(resultTask);
        if (remote.taskId() != null) {
            localToRemoteTask.put(task.taskId(), remote.taskId());
        }
        log.debug("A2A task {} -> remote {} status {}",
                task.taskId(), remote.taskId(),
                remote.status() == null ? "?" : remote.status().label());

        A2ATaskStatus status = remote.status();
        if (status == A2ATaskStatus.COMPLETED) {
            ObjectNode out = A2AJson.mapper().createObjectNode();
            out.put("output", remote.artifacts().isEmpty() ? "" : remote.artifacts().get(0).text());
            out.put("taskId", remote.taskId());
            if (remote.contextId() != null) {
                out.put("contextId", remote.contextId());
            }
            return out;
        }
        if (status == A2ATaskStatus.INPUT_REQUIRED) {
            // The peer paused mid-task: data, not an exception (it did work).
            ObjectNode out = A2AJson.mapper().createObjectNode();
            out.put("status", status.label());
            out.put("taskId", remote.taskId());
            if (remote.contextId() != null) {
                out.put("contextId", remote.contextId());
            }
            return out;
        }
        // failed / canceled / rejected: same semantics as InProcessA2AClient.
        String message = resultTask.path("status").path("message").isTextual()
                ? resultTask.path("status").path("message").asText() : "";
        throw new IllegalStateException("A2A task '" + task.taskId()
                + "' ended in state '" + (status == null ? "unknown" : status.label())
                + "' on " + baseUrl + (message.isBlank() ? "" : ": " + message));
    }

    /**
     * Poll a task previously sent through THIS client instance (local id).
     * Returns null for tasks never sent here or no longer known remotely
     * (spec error -32001).
     */
    @Override
    public A2ATaskStatus getTaskStatus(String taskId) {
        String remoteId = localToRemoteTask.get(taskId);
        if (remoteId == null) {
            return null;
        }
        ObjectNode request = A2AJson.tasksGetRequest(rpcIds.getAndIncrement(), remoteId);
        JsonNode resultTask;
        try {
            resultTask = rpc(request).path("result");
        } catch (A2AHttpException e) {
            if (e.code() == TASK_NOT_FOUND) {
                return null;
            }
            throw e;
        }
        return A2AJson.taskFrom(resultTask).status();
    }

    // ============ Messages ============

    /**
     * v1: not supported over HTTP. The spec has no fire-and-forget message
     * method -- mapping this onto message/send would silently CREATE a task
     * on the peer, which is not what the caller asked for. Loud refusal
     * instead of a quiet lie.
     */
    @Override
    public void sendMessage(A2AMessage message) {
        throw new UnsupportedOperationException(
                "v1 HTTP transport implements task delegation (message/send, tasks/get) only; "
                + "fire-and-forget messages are not in the A2A subset this version speaks");
    }

    // ============ Transport ============

    private JsonNode rpc(JsonNode request) {
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .build();
            HttpResponse<String> response =
                    http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new A2AHttpException(A2AHttpException.TRANSPORT,
                        "A2A endpoint " + baseUrl + " returned HTTP " + response.statusCode()
                                + snippet(response.body()), null);
            }
            JsonNode parsed = A2AJson.mapper().readTree(response.body());
            if (parsed.has("error")) {
                JsonNode error = parsed.get("error");
                throw new A2AHttpException(
                        error.path("code").asInt(A2AHttpException.TRANSPORT),
                        "A2A JSON-RPC error from " + baseUrl + ": "
                                + error.path("message").asText("unknown error"), null);
            }
            return parsed;
        } catch (A2AHttpException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new A2AHttpException(A2AHttpException.TRANSPORT,
                    "A2A transport failure to " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private String getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new A2AHttpException(A2AHttpException.TRANSPORT,
                        "agent card fetch " + url + " returned HTTP "
                                + response.statusCode() + snippet(response.body()), null);
            }
            return response.body();
        } catch (A2AHttpException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new A2AHttpException(A2AHttpException.TRANSPORT,
                    "agent card fetch " + url + " failed: " + e.getMessage(), e);
        }
    }

    private static String snippet(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ");
        return flat.length() <= 120 ? " (" + flat + ")" : " (" + flat.substring(0, 120) + "...)";
    }
}

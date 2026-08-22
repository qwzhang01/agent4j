package io.github.qwzhang01.agent.model.videogen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.client.VideoGenerationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * VideoGenerationClient for the OpenAI Video API (Sora).
 * <p>
 * Workflow (task-based, asynchronous):
 * - submit: POST {base}/videos
 *   body: {model: "sora-2", prompt, seconds?, size?}
 * - status: GET {base}/videos/{id}
 *   resp: {id, status: queued|in_progress|completed|failed, progress, error}
 * - download: GET {base}/videos/{id}/content (requires auth header)
 * <p>
 * Unlike Ark Seedance, Sora does not expose a public video URL - use
 * {@link #downloadContent} to fetch the mp4 bytes of a completed task.
 */
public class OpenAiVideoClient implements VideoGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVideoClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    // ============ Constructors ============

    public OpenAiVideoClient(String apiKey) {
        this("https://api.openai.com/v1", apiKey, "sora-2");
    }

    public OpenAiVideoClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(60));
    }

    public OpenAiVideoClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ============ VideoGenerationClient ============

    @Override
    public VideoTask submit(VideoGenRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/videos"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(), response.body());
            }
            return parseTask(response.body());
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to submit OpenAI video task: " + e.getMessage(), e);
        }
    }

    @Override
    public VideoTask status(String taskId) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/videos/" + taskId))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(), response.body());
            }
            return parseTask(response.body());
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to query OpenAI video task " + taskId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] downloadContent(String taskId) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/videos/" + taskId + "/content"))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(),
                        "content download failed with status " + response.statusCode());
            }
            return response.body();
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to download OpenAI video content for " + taskId + ": " + e.getMessage(), e);
        }
    }

    // ============ Request Building ============

    private ObjectNode buildRequestBody(VideoGenRequest request, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.prompt());

        if (request.seconds() != null) {
            body.put("seconds", request.seconds());
        }
        if (request.size() != null && !request.size().isBlank()) {
            body.put("size", request.size());
        }

        return body;
    }

    // ============ Response Parsing ============

    /**
     * Parses an OpenAI video task payload (submit response or status response).
     */
    private VideoTask parseTask(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "No task id in response: " + responseBody);
            }

            String status = mapStatus(root.path("status").asText("queued"));
            Integer progress = root.has("progress") && root.path("progress").isInt()
                    ? root.path("progress").asInt() : null;

            String error = null;
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                error = errorNode.path("message").asText("Unknown error");
                status = VideoTask.STATUS_FAILED;
            }

            // Sora tasks have no public video URL; content must be downloaded
            return new VideoTask(id, status, null, null, progress, error);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse OpenAI video task response: " + e.getMessage(), e);
        }
    }

    /**
     * Maps OpenAI status values to our unified status constants.
     * OpenAI: queued / in_progress / completed / failed
     */
    private String mapStatus(String status) {
        return switch (status) {
            case "completed" -> VideoTask.STATUS_SUCCEEDED;
            case "failed" -> VideoTask.STATUS_FAILED;
            case "in_progress" -> VideoTask.STATUS_RUNNING;
            default -> VideoTask.STATUS_QUEUED;
        };
    }

    // ============ Error Handling ============

    private ModelException parseError(int statusCode, String body) {
        return switch (statusCode) {
            case 401 -> new ModelException(ModelException.ErrorCode.AUTH_ERROR,
                    "OpenAI authentication failed (401): " + body);
            case 429 -> new ModelException(ModelException.ErrorCode.RATE_LIMITED,
                    "OpenAI rate limited (429): " + body);
            case 400 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "OpenAI invalid request (400): " + body);
            case 404 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "OpenAI task not found (404): " + body);
            default -> {
                if (statusCode >= 500) {
                    yield new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "OpenAI server error (" + statusCode + "): " + body);
                }
                yield new ModelException(ModelException.ErrorCode.UNKNOWN,
                        "Unexpected OpenAI status (" + statusCode + "): " + body);
            }
        };
    }
}

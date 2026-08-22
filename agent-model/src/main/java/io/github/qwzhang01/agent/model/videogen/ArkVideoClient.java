package io.github.qwzhang01.agent.model.videogen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
 * VideoGenerationClient for Volcengine Ark (Seedance) content generation tasks.
 * <p>
 * Workflow (task-based, asynchronous):
 * - submit: POST {base}/contents/generations/tasks
 *   body: {model, prompt, image?: [urls], duration?, ratio?}
 * - status: GET {base}/contents/generations/tasks/{id}
 *   resp: {id, status: queued|running|succeeded|failed|cancelled,
 *          content: {video_url}, error: {code, message}}
 * <p>
 * Succeeded tasks expose a public video URL (no download step needed).
 * Default base URL: https://ark.cn-beijing.volces.com/api/v3
 * Example model: "doubao-seedance-1-0-pro-250528"
 */
public class ArkVideoClient implements VideoGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(ArkVideoClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3";

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    // ============ Constructors ============

    public ArkVideoClient(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey, "doubao-seedance-1-0-pro-250528");
    }

    public ArkVideoClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(60));
    }

    public ArkVideoClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
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
                .uri(URI.create(baseUrl + "/contents/generations/tasks"))
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
                    "Failed to submit Ark video task: " + e.getMessage(), e);
        }
    }

    @Override
    public VideoTask status(String taskId) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/contents/generations/tasks/" + taskId))
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
                    "Failed to query Ark video task " + taskId + ": " + e.getMessage(), e);
        }
    }

    // ============ Request Building ============

    private ObjectNode buildRequestBody(VideoGenRequest request, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.prompt());

        if (request.seconds() != null) {
            body.put("duration", request.seconds());
        }
        if (request.ratio() != null && !request.ratio().isBlank()) {
            body.put("ratio", request.ratio());
        }

        // First-frame / reference images for image-to-video
        if (request.referenceImageUrls() != null && !request.referenceImageUrls().isEmpty()) {
            ArrayNode images = body.putArray("image");
            request.referenceImageUrls().forEach(images::add);
        }

        return body;
    }

    // ============ Response Parsing ============

    /**
     * Parses an Ark task payload (submit response or status response).
     */
    private VideoTask parseTask(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // Check for API-level error envelope
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode() && !errorNode.isNull()) {
                String errorMsg = errorNode.path("message").asText("Unknown error");
                return new VideoTask(
                        root.path("id").asText(null),
                        VideoTask.STATUS_FAILED,
                        null, null, null, errorMsg);
            }

            String id = root.path("id").asText(null);
            if (id == null || id.isBlank()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "No task id in response: " + responseBody);
            }

            String status = mapStatus(root.path("status").asText("queued"));
            String videoUrl = root.path("content").path("video_url").asText(null);
            String coverUrl = root.path("content").path("cover_image_url").asText(null);

            return new VideoTask(id, status, videoUrl, coverUrl, null, null);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse Ark video task response: " + e.getMessage(), e);
        }
    }

    /**
     * Maps Ark status values to our unified status constants.
     * Ark: queued / running / succeeded / failed / cancelled
     */
    private String mapStatus(String status) {
        return switch (status) {
            case "succeeded" -> VideoTask.STATUS_SUCCEEDED;
            case "failed", "cancelled" -> VideoTask.STATUS_FAILED;
            case "running" -> VideoTask.STATUS_RUNNING;
            default -> VideoTask.STATUS_QUEUED;
        };
    }

    // ============ Error Handling ============

    private ModelException parseError(int statusCode, String body) {
        return switch (statusCode) {
            case 401 -> new ModelException(ModelException.ErrorCode.AUTH_ERROR,
                    "Ark authentication failed (401): " + body);
            case 429 -> new ModelException(ModelException.ErrorCode.RATE_LIMITED,
                    "Ark rate limited (429): " + body);
            case 400 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "Ark invalid request (400): " + body);
            case 404 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "Ark task not found (404): " + body);
            default -> {
                if (statusCode >= 500) {
                    yield new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "Ark server error (" + statusCode + "): " + body);
                }
                yield new ModelException(ModelException.ErrorCode.UNKNOWN,
                        "Unexpected Ark status (" + statusCode + "): " + body);
            }
        };
    }
}

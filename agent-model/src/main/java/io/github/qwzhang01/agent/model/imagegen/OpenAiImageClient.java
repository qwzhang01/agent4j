package io.github.qwzhang01.agent.model.imagegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ImageGenerationClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * ImageGenerationClient for OpenAI-compatible image APIs (synchronous).
 * <p>
 * Works with:
 * - OpenAI Images API (gpt-image-1, dall-e-3): https://api.openai.com/v1
 * - Volcengine Ark Seedream: https://ark.cn-beijing.volces.com/api/v3
 *   (same /images/generations wire format, model e.g. "doubao-seedream-4-0-250828")
 * <p>
 * Notes:
 * - gpt-image-1 always returns base64 and does not accept response_format
 * - dall-e-3 returns URLs by default and supports response_format url/b64_json
 * - referenceImageUrls (image-to-image) is emitted as the "image" array,
 *   understood by Ark Seedream; OpenAI ignores/omits it
 */
public class OpenAiImageClient implements ImageGenerationClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiImageClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    // ============ Constructors ============

    public OpenAiImageClient(String apiKey) {
        this("https://api.openai.com/v1", apiKey, "gpt-image-1");
    }

    public OpenAiImageClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(120));
    }

    public OpenAiImageClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ============ ImageGenerationClient ============

    @Override
    public ImageResult generate(ImageGenRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/images/generations"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(), response.body());
            }

            return parseResponse(response.body(), model);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to call image generation API: " + e.getMessage(), e);
        }
    }

    // ============ Request Building ============

    private ObjectNode buildRequestBody(ImageGenRequest request, String model) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("prompt", request.prompt());

        if (request.n() != null) {
            body.put("n", request.n());
        }
        if (request.size() != null && !request.size().isBlank()) {
            body.put("size", request.size());
        }
        if (request.quality() != null && !request.quality().isBlank()) {
            body.put("quality", request.quality());
        }

        // response_format is a DALL-E parameter; gpt-image models reject it
        if (request.responseFormat() != null && !request.responseFormat().isBlank()
                && !model.contains("gpt-image")) {
            body.put("response_format", request.responseFormat());
        }

        // Reference images for image-to-image (Ark Seedream "image" array)
        if (request.referenceImageUrls() != null && !request.referenceImageUrls().isEmpty()) {
            ArrayNode images = body.putArray("image");
            request.referenceImageUrls().forEach(images::add);
        }

        return body;
    }

    // ============ Response Parsing ============

    private ImageResult parseResponse(String responseBody, String model) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode dataArray = root.path("data");
            if (dataArray.isMissingNode() || !dataArray.isArray() || dataArray.isEmpty()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "No images in response: " + responseBody);
            }

            List<GeneratedImage> images = new ArrayList<>();
            for (JsonNode item : dataArray) {
                String url = item.path("url").asText(null);
                String b64 = item.path("b64_json").asText(null);
                String revised = item.has("revised_prompt") ? item.path("revised_prompt").asText(null) : null;
                String size = item.path("size").asText(null);
                images.add(new GeneratedImage(url, b64, revised, size));
            }

            return new ImageResult(images, model);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse image generation response: " + e.getMessage(), e);
        }
    }

    // ============ Error Handling ============

    private ModelException parseError(int statusCode, String body) {
        return switch (statusCode) {
            case 401 -> new ModelException(ModelException.ErrorCode.AUTH_ERROR,
                    "Authentication failed (401): " + body);
            case 429 -> new ModelException(ModelException.ErrorCode.RATE_LIMITED,
                    "Rate limited (429): " + body);
            case 400 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "Invalid request (400): " + body);
            case 408 -> new ModelException(ModelException.ErrorCode.TIMEOUT,
                    "Request timeout (408): " + body);
            default -> {
                if (statusCode >= 500) {
                    yield new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "Server error (" + statusCode + "): " + body);
                }
                yield new ModelException(ModelException.ErrorCode.UNKNOWN,
                        "Unexpected status (" + statusCode + "): " + body);
            }
        };
    }
}

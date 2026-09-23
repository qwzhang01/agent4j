package io.github.qwzhang01.agent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.EmbeddingClient;
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
import java.util.Objects;

/**
 * EmbeddingClient implementation for OpenAI-compatible embedding APIs.
 * <p>
 * Works with the same endpoint family as {@link OpenAiModelClient}:
 * OpenAI, Azure OpenAI, Ollama / vLLM / LM Studio (OpenAI-compatible mode),
 * Volcengine Ark, Qwen / DashScope, DeepSeek, OpenRouter, Moonshot.
 * <p>
 * Uses Java 21's built-in HttpClient. Supports single and batch embedding
 * ({@code POST /embeddings} with an array of inputs). Vector values are read
 * as JSON numbers into a {@code float[]}, matching the port's contract.
 */
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final Duration requestTimeout;

    public OpenAiEmbeddingClient(String apiKey) {
        this("https://api.openai.com/v1", apiKey, "text-embedding-3-small");
    }

    public OpenAiEmbeddingClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(30));
    }

    /**
     * @param baseUrl      endpoint base URL, e.g. {@code https://api.openai.com/v1};
     *                     trailing slash tolerated
     * @param apiKey       bearer token
     * @param defaultModel embedding model used when a call carries none
     * @param timeout      per-request read timeout; null = 30s
     */
    public OpenAiEmbeddingClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.requestTimeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
        log.debug("OpenAiEmbeddingClient initialized: baseUrl={}, model={}", this.baseUrl, defaultModel);
    }

    // EmbeddingClient

    @Override
    public float[] embed(String text) {
        validateText(text);
        float[][] results = request(new String[]{text});
        return results[0];
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts must not be null or empty");
        }
        for (String text : texts) {
            validateText(text);
        }
        float[][] results = request(texts.toArray(new String[0]));
        return List.of(results);
    }

    // Internals

    private static void validateText(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be null or blank");
        }
    }

    /**
     * Issues one POST /embeddings for the given inputs and returns the
     * provider-ordered vectors.
     */
    private float[][] request(String[] inputs) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", defaultModel);
        ArrayNode input = body.putArray("input");
        for (String s : inputs) {
            input.add(s);
        }

        HttpRequest httpRequest = newRequestBuilder(body).build();

        try {
            HttpResponse<String> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(), response.body());
            }
            return parseVectors(response.body(), inputs.length);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to call embedding API: " + e.getMessage(), e);
        }
    }

    private HttpRequest.Builder newRequestBuilder(ObjectNode body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    /**
     * Parses the OpenAI embeddings response. The API may return data items in
     * any order, but guarantees each item carries its input index; this parser
     * re-orders by {@code index} so the output is input-aligned even when the
     * provider shuffles.
     */
    private float[][] parseVectors(String responseBody, int expected) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.size() != expected) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "Embedding response missing 'data' array (expected " + expected
                                + " items, got " + (data == null ? "null" : data.size()) + ")");
            }
            float[][] vectors = new float[expected][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                JsonNode embedding = item.get("embedding");
                if (index < 0 || index >= expected || embedding == null || !embedding.isArray()) {
                    throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "Embedding response item missing 'index' or 'embedding'");
                }
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < embedding.size(); i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors[index] = vector;
            }
            for (float[] v : vectors) {
                if (v == null) {
                    throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "Embedding response incomplete: missing vector for some input index");
                }
            }
            return vectors;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse embedding response: " + e.getMessage(), e);
        }
    }

    /**
     * Maps HTTP status to the same error-code taxonomy as {@link OpenAiModelClient}.
     */
    private ModelException parseError(int statusCode, String body) {
        return switch (statusCode) {
            case 401 -> new ModelException(ModelException.ErrorCode.AUTH_ERROR,
                    "Embedding API auth failed (" + statusCode + "): " + body);
            case 429 -> new ModelException(ModelException.ErrorCode.RATE_LIMITED,
                    "Embedding API rate limited (" + statusCode + "): " + body);
            case 400 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "Embedding API rejected request (" + statusCode + "): " + body);
            case 408 -> new ModelException(ModelException.ErrorCode.TIMEOUT,
                    "Embedding API timeout (" + statusCode + "): " + body);
            case 500, 502, 503 -> new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Embedding API server error (" + statusCode + "): " + body);
            default -> new ModelException(ModelException.ErrorCode.UNKNOWN,
                    "Embedding API error (" + statusCode + "): " + body);
        };
    }
}

package com.seven.agent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.seven.agent.core.client.ModelClient;
import com.seven.agent.core.client.ModelException;
import com.seven.agent.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * ModelClient implementation for OpenAI-compatible APIs.
 * <p>
 * Works with:
 * - OpenAI (api.openai.com)
 * - Azure OpenAI
 * - Local models via Ollama / vLLM / LM Studio (OpenAI-compatible mode)
 * - Volcengine Ark (火山方舟) with OpenAI-compatible endpoint
 * <p>
 * Uses Java 21's built-in HttpClient (no external HTTP library needed).
 * Supports:
 * - Chat completion (sync)
 * - Streaming (SSE)
 * - Tool calling
 * - Structured output (response_format)
 */
public class OpenAiModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;

    public OpenAiModelClient(String apiKey) {
        this("https://api.openai.com/v1", apiKey, "gpt-4o-mini");
    }

    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(60));
    }

    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ============ ModelClient ============

    @Override
    public ModelResponse chat(ModelRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model, false);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                throw parseError(response.statusCode(), response.body());
            }

            return parseResponse(response.body());
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Failed to call model API: " + e.getMessage(), e);
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model, true);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        // Create a stream that reads SSE lines lazily
        return Stream.generate(() -> {
            try {
                return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
            } catch (Exception e) {
                throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                        "Stream request failed: " + e.getMessage(), e);
            }
        }).limit(1).flatMap(response -> {
            if (response.statusCode() >= 400) {
                String errorBody = response.body().findFirst().orElse("Unknown error");
                throw parseError(response.statusCode(), errorBody);
            }
            return parseSseStream(response.body());
        });
    }

    // ============ Request Building ============

    private ObjectNode buildRequestBody(ModelRequest request, String model, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);

        // Messages
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage msg : request.messages()) {
            ObjectNode msgNode = messages.addObject();
            msgNode.put("role", msg.role().name().toLowerCase());
            if (msg.content() != null) {
                msgNode.put("content", msg.content());
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                ArrayNode toolCalls = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCalls.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode func = tcNode.putObject("function");
                    func.put("name", tc.name());
                    func.put("arguments", tc.arguments() != null ? tc.arguments().toString() : "{}");
                }
            }
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
            if (msg.name() != null) {
                msgNode.put("name", msg.name());
            }
        }

        // Tools
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (String toolSchema : request.tools()) {
                try {
                    JsonNode schemaNode = mapper.readTree(toolSchema);
                    ObjectNode toolNode = tools.addObject();
                    toolNode.put("type", "function");
                    // Move name/description/parameters into function object
                    ObjectNode func = toolNode.putObject("function");
                    func.put("name", schemaNode.path("name").asText());
                    func.put("description", schemaNode.path("description").asText());
                    func.set("parameters", schemaNode.path("parameters"));
                } catch (Exception e) {
                    log.warn("Failed to parse tool schema: {}", toolSchema, e);
                }
            }
        }

        // Optional params
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }

        // Response format (structured output)
        if (request.responseFormat() != null) {
            ObjectNode fmt = body.putObject("response_format");
            fmt.put("type", request.responseFormat().type());
            if (request.responseFormat().jsonSchema() != null) {
                ObjectNode schema = fmt.putObject("json_schema");
                schema.put("name", "structured_output");
                try {
                    schema.set("schema", mapper.readTree(request.responseFormat().jsonSchema()));
                } catch (Exception e) {
                    log.warn("Failed to parse JSON schema for response_format", e);
                }
            }
        }

        return body;
    }

    // ============ Response Parsing ============

    private ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode choices = root.path("choices");
            if (choices.isMissingNode() || !choices.isArray() || choices.isEmpty()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "No choices in response: " + responseBody);
            }

            JsonNode choice = choices.get(0);
            JsonNode message = choice.path("message");
            String finishReason = choice.path("finish_reason").asText("stop");
            String content = message.path("content").asText(null);

            // Parse tool calls
            List<ToolCall> toolCalls = null;
            JsonNode toolCallsNode = message.path("tool_calls");
            if (toolCallsNode.isArray() && !toolCallsNode.isEmpty()) {
                toolCalls = new ArrayList<>();
                for (JsonNode tc : toolCallsNode) {
                    String id = tc.path("id").asText();
                    String name = tc.path("function").path("name").asText();
                    String argsStr = tc.path("function").path("arguments").asText("{}");
                    try {
                        JsonNode args = mapper.readTree(argsStr);
                        toolCalls.add(ToolCall.of(id, name, args));
                    } catch (Exception e) {
                        log.warn("Failed to parse tool call arguments: {}", argsStr, e);
                        toolCalls.add(ToolCall.of(id, name, mapper.createObjectNode()));
                    }
                }
            }

            // Parse usage
            ModelResponse.TokenUsage usage = null;
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                usage = new ModelResponse.TokenUsage(
                        usageNode.path("prompt_tokens").asInt(0),
                        usageNode.path("completion_tokens").asInt(0),
                        usageNode.path("total_tokens").asInt(0)
                );
            }

            return new ModelResponse(content, toolCalls, finishReason, usage);
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse response: " + e.getMessage(), e);
        }
    }

    // ============ SSE Streaming Parsing ============

    private Stream<StreamEvent> parseSseStream(java.util.stream.Stream<String> lines) {
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCallsBuilder = new ArrayList<>();

        return lines
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6)) // strip "data: "
                .takeWhile(data -> !"[DONE]".equals(data.trim()))
                .map(data -> {
                    try {
                        JsonNode chunk = mapper.readTree(data);
                        JsonNode delta = chunk.path("choices").path(0).path("delta");

                        // Content delta
                        if (delta.has("content")) {
                            String deltaContent = delta.get("content").asText();
                            contentBuilder.append(deltaContent);
                            return (StreamEvent) new StreamEvent.ContentDelta(deltaContent);
                        }

                        // Tool call delta
                        if (delta.has("tool_calls")) {
                            JsonNode tcArray = delta.get("tool_calls");
                            for (JsonNode tc : tcArray) {
                                int index = tc.path("index").asInt(0);
                                String id = tc.path("id").asText();
                                String name = tc.path("function").path("name").asText();
                                String argsStr = tc.path("function").path("arguments").asText("");
                                if (id != null && !id.isEmpty()) {
                                    JsonNode args = argsStr.isEmpty()
                                            ? mapper.createObjectNode()
                                            : mapper.readTree(argsStr);
                                    toolCallsBuilder.add(ToolCall.of(id, name, args));
                                }
                            }
                        }

                        // Finish reason
                        JsonNode finishNode = chunk.path("choices").path(0).path("finish_reason");
                        if (!finishNode.isMissingNode() && !finishNode.isNull()) {
                            String finishReason = finishNode.asText();
                            ModelResponse finalResponse = new ModelResponse(
                                    contentBuilder.toString(),
                                    toolCallsBuilder.isEmpty() ? null : toolCallsBuilder,
                                    finishReason,
                                    null
                            );
                            return new StreamEvent.Done(finalResponse);
                        }

                        return null;
                    } catch (Exception e) {
                        return new StreamEvent.Error("Failed to parse SSE chunk: " + data, e);
                    }
                })
                .filter(event -> event != null);
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

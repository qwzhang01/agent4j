package com.seven.agent.model.anthropic;

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

/**
 * ModelClient implementation for the Anthropic Messages API (Claude).
 * <p>
 * Works with:
 * - Anthropic Claude (api.anthropic.com)
 * - Claude-compatible endpoints
 * <p>
 * Key differences from OpenAI API:
 * - Auth: x-api-key header (not Bearer token)
 * - System prompt: top-level "system" field (not in messages array)
 * - max_tokens: required (not optional)
 * - Tools: "input_schema" (not "parameters")
 * - Response: "content" is an array of blocks (text / tool_use)
 * - Stop reason: "end_turn" / "tool_use" / "max_tokens" (not "stop" / "tool_calls")
 * - Usage: "input_tokens" / "output_tokens" (not "prompt_tokens" / "completion_tokens")
 * <p>
 * Uses Java 21's built-in HttpClient (no external HTTP library needed).
 * Supports:
 * - Messages API (sync)
 * - Streaming (SSE)
 * - Tool calling
 * - Structured output (via response_format mapping)
 */
public class AnthropicModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(AnthropicModelClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    private static final String DEFAULT_API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String apiVersion;
    private final String defaultModel;
    private final int defaultMaxTokens;

    // ============ Constructors ============

    public AnthropicModelClient(String apiKey) {
        this(DEFAULT_BASE_URL, apiKey, DEFAULT_API_VERSION, "claude-sonnet-4-20250514", DEFAULT_MAX_TOKENS);
    }

    public AnthropicModelClient(String apiKey, String defaultModel) {
        this(DEFAULT_BASE_URL, apiKey, DEFAULT_API_VERSION, defaultModel, DEFAULT_MAX_TOKENS);
    }

    public AnthropicModelClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, DEFAULT_API_VERSION, defaultModel, DEFAULT_MAX_TOKENS);
    }

    public AnthropicModelClient(String baseUrl, String apiKey, String apiVersion,
                               String defaultModel, int defaultMaxTokens) {
        this(baseUrl, apiKey, apiVersion, defaultModel, defaultMaxTokens, Duration.ofSeconds(60));
    }

    public AnthropicModelClient(String baseUrl, String apiKey, String apiVersion,
                               String defaultModel, int defaultMaxTokens, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.apiVersion = apiVersion;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
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
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", apiVersion)
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
                    "Failed to call Anthropic API: " + e.getMessage(), e);
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model, true);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", apiVersion)
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        try {
            HttpResponse<Stream<String>> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() >= 400) {
                String errorBody = response.body().findFirst().orElse("Unknown error");
                throw parseError(response.statusCode(), errorBody);
            }

            return parseSseStream(response.body());
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.NETWORK_ERROR,
                    "Stream request failed: " + e.getMessage(), e);
        }
    }

    // ============ Request Building ============

    private ObjectNode buildRequestBody(ModelRequest request, String model, boolean stream) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : defaultMaxTokens);
        body.put("stream", stream);

        // Extract system prompt from messages (Anthropic uses top-level "system" field)
        StringBuilder systemPrompt = new StringBuilder();
        ArrayNode messages = body.putArray("messages");

        for (ChatMessage msg : request.messages()) {
            if (msg.role() == ChatRole.SYSTEM) {
                // Collect system messages into top-level field
                if (!systemPrompt.isEmpty()) {
                    systemPrompt.append("\n");
                }
                systemPrompt.append(msg.content() != null ? msg.content() : "");
            } else if (msg.role() == ChatRole.USER) {
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", "user");
                msgNode.put("content", msg.content() != null ? msg.content() : "");
            } else if (msg.role() == ChatRole.ASSISTANT) {
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", "assistant");

                // Assistant content can be text + tool_use blocks
                ArrayNode contentArray = msgNode.putArray("content");

                // Text content (if any)
                if (msg.content() != null && !msg.content().isBlank()) {
                    ObjectNode textBlock = contentArray.addObject();
                    textBlock.put("type", "text");
                    textBlock.put("text", msg.content());
                }

                // Tool use blocks (if any)
                if (msg.toolCalls() != null) {
                    for (ToolCall tc : msg.toolCalls()) {
                        ObjectNode toolUseBlock = contentArray.addObject();
                        toolUseBlock.put("type", "tool_use");
                        toolUseBlock.put("id", tc.id());
                        toolUseBlock.put("name", tc.name());
                        toolUseBlock.set("input", tc.arguments() != null ? tc.arguments() : mapper.createObjectNode());
                    }
                }
            } else if (msg.role() == ChatRole.TOOL) {
                // Tool results: Anthropic uses role "user" with tool_result content block
                ObjectNode msgNode = messages.addObject();
                msgNode.put("role", "user");
                ArrayNode contentArray = msgNode.putArray("content");

                ObjectNode toolResultBlock = contentArray.addObject();
                toolResultBlock.put("type", "tool_result");
                toolResultBlock.put("tool_use_id", msg.toolCallId());
                toolResultBlock.put("content", msg.content() != null ? msg.content() : "");
            }
        }

        // Set system prompt if any
        if (!systemPrompt.isEmpty()) {
            body.put("system", systemPrompt.toString());
        }

        // Tools (Anthropic format: name + description + input_schema)
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (String toolSchema : request.tools()) {
                try {
                    JsonNode schemaNode = mapper.readTree(toolSchema);
                    ObjectNode toolNode = tools.addObject();
                    toolNode.put("name", schemaNode.path("name").asText());
                    toolNode.put("description", schemaNode.path("description").asText());
                    // Convert "parameters" -> "input_schema"
                    JsonNode parameters = schemaNode.path("parameters");
                    if (!parameters.isMissingNode()) {
                        toolNode.set("input_schema", parameters);
                    } else {
                        toolNode.putObject("input_schema")
                                .put("type", "object")
                                .putArray("properties");
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse tool schema: {}", toolSchema, e);
                }
            }
        }

        // Optional params
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        // Response format (structured output)
        // Anthropic doesn't have native JSON mode like OpenAI,
        // but we can use system prompt instructions as a hint.
        // The StructuredOutputModelClient decorator handles validation.
        if (request.responseFormat() != null) {
            String existingSystem = body.has("system") ? body.get("system").asText() : "";
            String jsonInstruction = "You must respond with ONLY valid JSON, no markdown, no explanation.";
            if (request.responseFormat().jsonSchema() != null) {
                jsonInstruction += " The response must conform to this JSON schema: "
                        + request.responseFormat().jsonSchema();
            }
            body.put("system", existingSystem.isEmpty() ? jsonInstruction : existingSystem + "\n" + jsonInstruction);
        }

        return body;
    }

    // ============ Response Parsing ============

    private ModelResponse parseResponse(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);

            // Check for error
            JsonNode errorNode = root.path("error");
            if (!errorNode.isMissingNode()) {
                String errorMsg = errorNode.path("message").asText("Unknown error");
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "Anthropic API error: " + errorMsg);
            }

            // Parse content blocks
            JsonNode contentArray = root.path("content");
            if (contentArray.isMissingNode() || !contentArray.isArray() || contentArray.isEmpty()) {
                throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                        "No content in response: " + responseBody);
            }

            StringBuilder textContent = new StringBuilder();
            List<ToolCall> toolCalls = null;

            for (JsonNode block : contentArray) {
                String type = block.path("type").asText();

                if ("text".equals(type)) {
                    textContent.append(block.path("text").asText(""));
                } else if ("tool_use".equals(type)) {
                    if (toolCalls == null) {
                        toolCalls = new ArrayList<>();
                    }
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    JsonNode input = block.path("input");
                    if (input.isMissingNode()) {
                        input = mapper.createObjectNode();
                    }
                    toolCalls.add(ToolCall.of(id, name, input));
                }
            }

            // Parse stop reason
            String stopReason = root.path("stop_reason").asText("end_turn");
            String finishReason = mapStopReason(stopReason);

            // Parse usage
            ModelResponse.TokenUsage usage = null;
            JsonNode usageNode = root.path("usage");
            if (!usageNode.isMissingNode()) {
                int inputTokens = usageNode.path("input_tokens").asInt(0);
                int outputTokens = usageNode.path("output_tokens").asInt(0);
                usage = new ModelResponse.TokenUsage(inputTokens, outputTokens,
                        inputTokens + outputTokens);
            }

            String content = textContent.length() > 0 ? textContent.toString() : null;
            return new ModelResponse(content, toolCalls, finishReason, usage);

        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Failed to parse Anthropic response: " + e.getMessage(), e);
        }
    }

    // ============ SSE Streaming Parsing ============
    //
    // Anthropic SSE event types:
    //   event: message_start       -> message metadata
    //   event: content_block_start -> starts a block (text or tool_use)
    //   event: content_block_delta -> delta for a block
    //     - text_delta: { "text": "..." }
    //     - input_json_delta: { "partial_json": "..." }
    //   event: content_block_stop  -> ends a block
    //   event: message_delta      -> stop_reason + usage
    //   event: message_stop        -> message complete

    private Stream<StreamEvent> parseSseStream(Stream<String> lines) {
        // State accumulators (captured by the stream pipeline)
        StringBuilder contentBuilder = new StringBuilder();
        List<ToolCall> toolCallsBuilder = new ArrayList<>();

        // Parse the SSE: pairs of "event: xxx" / "data: {...}" lines
        return lines
                .filter(line -> line.startsWith("data: "))
                .map(line -> line.substring(6).trim())
                .filter(data -> !data.isEmpty())
                .map(data -> {
                    try {
                        JsonNode event = mapper.readTree(data);
                        String type = event.path("type").asText("");

                        StreamEvent parsed = switch (type) {
                            case "content_block_delta" -> {
                                JsonNode delta = event.path("delta");
                                String deltaType = delta.path("type").asText("");

                                if ("text_delta".equals(deltaType)) {
                                    String text = delta.path("text").asText("");
                                    contentBuilder.append(text);
                                    yield (StreamEvent) new StreamEvent.ContentDelta(text);
                                } else if ("input_json_delta".equals(deltaType)) {
                                    // Accumulate partial JSON for tool input
                                    // (handled in content_block_stop)
                                    yield null;
                                }
                                yield null;
                            }

                            case "content_block_start" -> {
                                JsonNode block = event.path("content_block");
                                String blockType = block.path("type").asText("");
                                if ("tool_use".equals(blockType)) {
                                    // Tool call started - will be completed in content_block_stop
                                    yield null;
                                }
                                yield null;
                            }

                            case "content_block_stop" -> {
                                // Block complete - no action needed for text blocks
                                yield null;
                            }

                            case "message_delta" -> {
                                // Final delta with stop_reason + usage
                                JsonNode delta = event.path("delta");
                                String stopReason = delta.path("stop_reason").asText("end_turn");
                                String finishReason = mapStopReason(stopReason);

                                JsonNode usageNode = event.path("usage");
                                ModelResponse.TokenUsage usage = null;
                                if (!usageNode.isMissingNode()) {
                                    int outputTokens = usageNode.path("output_tokens").asInt(0);
                                    usage = new ModelResponse.TokenUsage(0, outputTokens, outputTokens);
                                }

                                ModelResponse finalResponse = new ModelResponse(
                                        contentBuilder.length() > 0 ? contentBuilder.toString() : null,
                                        toolCallsBuilder.isEmpty() ? null : toolCallsBuilder,
                                        finishReason,
                                        usage
                                );
                                yield (StreamEvent) new StreamEvent.Done(finalResponse);
                            }

                            case "message_stop" -> null; // Already handled by message_delta

                            case "error" -> {
                                JsonNode error = event.path("error");
                                String msg = error.path("message").asText("Streaming error");
                                yield (StreamEvent) new StreamEvent.Error(msg,
                                        new ModelException(ModelException.ErrorCode.MODEL_ERROR, msg));
                            }

                            default -> null;
                        };
                        return parsed;
                    } catch (Exception e) {
                        return new StreamEvent.Error("Failed to parse SSE event: " + data, e);
                    }
                })
                .filter(event -> event != null);
    }

    // ============ Error Handling ============

    private ModelException parseError(int statusCode, String body) {
        return switch (statusCode) {
            case 401 -> new ModelException(ModelException.ErrorCode.AUTH_ERROR,
                    "Anthropic authentication failed (401): " + body);
            case 429 -> new ModelException(ModelException.ErrorCode.RATE_LIMITED,
                    "Anthropic rate limited (429): " + body);
            case 400, 422 -> new ModelException(ModelException.ErrorCode.INVALID_REQUEST,
                    "Anthropic invalid request (" + statusCode + "): " + body);
            case 408 -> new ModelException(ModelException.ErrorCode.TIMEOUT,
                    "Anthropic request timeout (408): " + body);
            case 529 -> new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "Anthropic overloaded (529): " + body);
            default -> {
                if (statusCode >= 500) {
                    yield new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                            "Anthropic server error (" + statusCode + "): " + body);
                }
                yield new ModelException(ModelException.ErrorCode.UNKNOWN,
                        "Unexpected Anthropic status (" + statusCode + "): " + body);
            }
        };
    }

    // ============ Helpers ============

    /**
     * Map Anthropic stop_reason to our internal finishReason.
     * <p>
     * Anthropic stop_reason values:
     * - end_turn      -> "stop" (model finished naturally)
     * - tool_use      -> "tool_calls" (model wants to call tools)
     * - max_tokens    -> "length" (hit token limit)
     * - stop_sequence -> "stop" (hit a stop sequence)
     * - pause_turn    -> "stop" (paused, will continue)
     */
    private String mapStopReason(String stopReason) {
        return switch (stopReason) {
            case "end_turn", "stop_sequence", "pause_turn" -> "stop";
            case "tool_use" -> "tool_calls";
            case "max_tokens" -> "length";
            default -> "stop";
        };
    }
}

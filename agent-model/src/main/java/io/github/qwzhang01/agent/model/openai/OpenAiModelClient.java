package io.github.qwzhang01.agent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * ModelClient implementation for OpenAI-compatible APIs.
 * <p>
 * Works with:
 * - OpenAI (api.openai.com)
 * - Azure OpenAI
 * - Local models via Ollama / vLLM / LM Studio (OpenAI-compatible mode)
 * - Volcengine Ark (火山方舟) with OpenAI-compatible endpoint
 * - Alibaba Qwen / DashScope, DeepSeek, OpenRouter, Moonshot
 * <p>
 * Uses Java 21's built-in HttpClient (no external HTTP library needed).
 * Supports:
 * - Chat completion (sync)
 * - Streaming (SSE)
 * - Tool calling
 * - Structured output (response_format)
 * - Multimodal vision (text + image messages via ChatMessage parts)
 * - Reasoning models (see below)
 *
 * <h2>Reasoning models</h2>
 * Models such as Ark {@code doubao-seed-*}, {@code deepseek-reasoner} and Qwen3
 * thinking mode stream their chain-of-thought in a field separate from
 * {@code content}. This client always keeps that channel out of the answer —
 * see {@link ChatDelta}, which reads every reasoning field name seen in the
 * wild rather than enumerating vendors.
 * <p>
 * Two complementary knobs control the request side:
 * <ol>
 *   <li>{@link ReasoningConfig} — the canonical, provider-neutral intent
 *       ({@code auto} / {@code enabled} / {@code disabled} plus an effort hint).
 *       Set per request via
 *       {@code ModelRequest.builder().reasoning(ReasoningConfig.disabled())}, or
 *       as a client-wide default via the constructor.</li>
 *   <li>{@code extraBody} — an escape hatch merged verbatim into every request
 *       body. Use it for vendor-specific fields the framework has no business
 *       modelling (Anthropic's {@code budget_tokens}, OpenAI's {@code include},
 *       or a switch this client has never heard of). This is what keeps the
 *       long tail usable without patching the framework for each new endpoint.</li>
 * </ol>
 * A request whose reasoning mode cannot be expressed for the detected endpoint
 * is logged as a warning rather than silently dropped.
 */
public class OpenAiModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiModelClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SSE_DATA_PREFIX = "data:";
    private static final String SSE_DONE_MARKER = "[DONE]";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final String defaultModel;
    private final Duration requestTimeout;
    private final Flavor flavor;
    private final ReasoningConfig defaultReasoning;
    private final Map<String, Object> extraBody;

    // ============ Constructors ============

    public OpenAiModelClient(String apiKey) {
        this("https://api.openai.com/v1", apiKey, "gpt-4o-mini");
    }

    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel) {
        this(baseUrl, apiKey, defaultModel, Duration.ofSeconds(60));
    }

    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel, Duration timeout) {
        this(baseUrl, apiKey, defaultModel, timeout, null, null);
    }

    /**
     * @param baseUrl          endpoint base URL (no trailing slash required)
     * @param apiKey           bearer token
     * @param defaultModel     model used when a request carries none
     * @param timeout          per-request read timeout; also bounds streams
     * @param flavor           endpoint identity, used only to pick the vendor's
     *                         reasoning switch; null auto-detects from {@code baseUrl}
     * @param defaultReasoning client-wide reasoning default; null means
     *                         {@link ReasoningConfig#auto()}. A request-level
     *                         config always wins.
     */
    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel, Duration timeout,
                             Flavor flavor, ReasoningConfig defaultReasoning) {
        this(baseUrl, apiKey, defaultModel, timeout, flavor, defaultReasoning, null);
    }

    /**
     * @param extraBody vendor-specific fields merged into every request body.
     *                  May be null. Existing standard fields win on collision,
     *                  so the escape hatch cannot corrupt the protocol.
     */
    public OpenAiModelClient(String baseUrl, String apiKey, String defaultModel, Duration timeout,
                             Flavor flavor, ReasoningConfig defaultReasoning,
                             Map<String, Object> extraBody) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.requestTimeout = timeout != null ? timeout : Duration.ofSeconds(60);
        this.flavor = flavor != null ? flavor : Flavor.forBaseUrl(baseUrl);
        this.defaultReasoning = defaultReasoning;
        this.extraBody = extraBody == null
                ? Map.of()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(extraBody));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT)
                .build();
        log.debug("OpenAiModelClient initialized: baseUrl={}, flavor={}, model={}",
                this.baseUrl, this.flavor, defaultModel);
    }

    // ============ ModelClient ============

    @Override
    public ModelResponse chat(ModelRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        ObjectNode body = buildRequestBody(request, model, false);

        HttpRequest httpRequest = newRequestBuilder(body)
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

        HttpRequest httpRequest = newRequestBuilder(body)
                .header("Accept", "text/event-stream")
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

    private HttpRequest.Builder newRequestBuilder(ObjectNode body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
    }

    /**
     * Request-level reasoning wins over the client default, which in turn wins
     * over {@link ReasoningConfig#auto()}.
     */
    private ReasoningConfig resolveReasoning(ModelRequest request) {
        if (request.reasoning() != null) {
            return request.reasoning();
        }
        return defaultReasoning != null ? defaultReasoning : ReasoningConfig.auto();
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
            // Multimodal parts (text + images) take precedence over plain text
            if (msg.parts() != null) {
                msgNode.set("content", buildContentParts(msg.parts()));
            } else if (msg.content() != null) {
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

        // Reasoning switch, translated per endpoint
        applyReasoning(body, resolveReasoning(request));

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

        // Escape hatch last: standard fields above win on collision, so a
        // vendor-specific override can never corrupt the protocol itself.
        mergeExtraBody(body);

        return body;
    }

    /**
     * Translates the canonical {@link ReasoningConfig} into this endpoint's
     * switch. Writes nothing for {@link ReasoningConfig.Mode#AUTO}, and logs a
     * warning when the requested mode has no representation here — silently
     * dropping a caller's intent is worse than telling them.
     */
    private void applyReasoning(ObjectNode body, ReasoningConfig config) {
        if (config.isAuto()) {
            return;
        }
        switch (flavor) {
            case ARK, ANTHROPIC_STYLE -> body.putObject("thinking")
                    .put("type", config.isDisabled() ? "disabled" : "enabled");
            case QWEN -> body.put("enable_thinking", config.isEnabled());
            case OPENROUTER -> {
                ObjectNode reasoning = body.putObject("reasoning");
                reasoning.put("exclude", config.isDisabled());
                if (config.isEnabled() && config.effort() != null && !config.effort().isBlank()) {
                    reasoning.put("effort", config.effort());
                }
            }
            case OPENAI -> {
                if (config.isDisabled()) {
                    log.warn("reasoning=disabled requested but the OpenAI o-series has no "
                            + "off-switch; the request is sent without one. Use extraBody "
                            + "to pass 'service_tier' or pick a non-reasoning model.");
                } else if (config.effort() != null && !config.effort().isBlank()) {
                    body.put("reasoning_effort", config.effort());
                }
            }
            case DEEPSEEK, GENERIC -> log.warn(
                    "reasoning={} requested but endpoint flavor {} exposes no reasoning "
                            + "switch; pass it via extraBody if this endpoint supports one. "
                            + "Reasoning output is still parsed and kept out of the answer.",
                    config.mode(), flavor);
        }
    }

    private void mergeExtraBody(ObjectNode body) {
        if (extraBody.isEmpty()) {
            return;
        }
        extraBody.forEach((key, value) -> {
            if (body.has(key)) {
                log.warn("extraBody key '{}' collides with a standard request field; ignoring it", key);
                return;
            }
            body.set(key, mapper.valueToTree(value));
        });
    }

    // ============ Multimodal Content Building ============

    /**
     * Builds the OpenAI multimodal content array:
     * <pre>
     * [
     *   {"type": "text", "text": "..."},
     *   {"type": "image_url", "image_url": {"url": "https://... or data:image/png;base64,..."}}
     * ]
     * </pre>
     * Base64 images are inlined as data URLs.
     */
    private ArrayNode buildContentParts(List<ContentPart> parts) {
        ArrayNode content = mapper.createArrayNode();
        for (ContentPart part : parts) {
            if (part instanceof ContentPart.TextPart tp) {
                content.addObject().put("type", "text").put("text", tp.text());
            } else if (part instanceof ContentPart.ImagePart ip) {
                ObjectNode p = content.addObject();
                p.put("type", "image_url");
                p.putObject("image_url").put("url", toDataUrl(ip));
            }
        }
        return content;
    }

    /**
     * Resolves an ImagePart into a URL string: public URL as-is,
     * base64 data as an inline data URL.
     */
    private String toDataUrl(ContentPart.ImagePart ip) {
        if (ip.url() != null && !ip.url().isBlank()) {
            return ip.url();
        }
        String mime = ip.mimeType() != null && !ip.mimeType().isBlank() ? ip.mimeType() : "image/png";
        return "data:" + mime + ";base64," + ip.base64Data();
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

            ChatDelta delta = new ChatDelta(message);
            String content = delta.content();

            // Reasoning is deliberately NOT merged into content: it is the model's
            // scratchpad, not its answer.
            String reasoning = delta.reasoning();
            if (reasoning != null) {
                log.debug("Model returned {} chars of reasoning; kept out of the answer",
                        reasoning.length());
            }

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

    /**
     * Converts raw SSE lines into {@link StreamEvent}s.
     * <p>
     * Each chunk may carry content, reasoning, tool-call fragments and
     * {@code finish_reason} <em>simultaneously</em> — Ark, for instance, sends
     * {@code {"delta":{"content":""},"finish_reason":"stop"}} as its last chunk.
     * A parser that returns early on the first recognized field therefore drops
     * the terminal event and the agent loop sees a stream that never completed.
     * {@link SseAccumulator} inspects every field of every chunk and is the sole
     * emitter of {@link StreamEvent.Done}.
     */
    private Stream<StreamEvent> parseSseStream(Stream<String> lines) {
        SseAccumulator accumulator = new SseAccumulator();

        Stream<StreamEvent> parsed = lines
                .filter(line -> line.startsWith(SSE_DATA_PREFIX))
                // Strip "data:" then a single optional space, per the SSE spec.
                // Do NOT trim the payload: leading/trailing spaces are meaningful
                // inside content deltas and trimming corrupts the answer.
                .map(OpenAiModelClient::stripDataPrefix)
                .takeWhile(data -> !SSE_DONE_MARKER.equals(data.trim()))
                .filter(data -> !data.isBlank())
                .flatMap(accumulator::consume);

        // Some gateways end the stream after [DONE] without ever sending
        // finish_reason. Append a lazily-evaluated fallback so downstream loops
        // always observe a terminal event instead of "stream ended without Done".
        Stream<StreamEvent> fallback = Stream.generate(accumulator::terminalFallback)
                .limit(1)
                .filter(Objects::nonNull);

        return Stream.concat(parsed, fallback);
    }

    private static String stripDataPrefix(String line) {
        String payload = line.substring(SSE_DATA_PREFIX.length());
        return payload.startsWith(" ") ? payload.substring(1) : payload;
    }

    /**
     * Stateful SSE chunk accumulator.
     * <p>
     * Streams are consumed exactly once and sequentially by
     * {@code ReActAgentLoop}, so plain fields suffice; no synchronization is
     * needed and none is implied.
     */
    private static final class SseAccumulator {

        private final StringBuilder content = new StringBuilder();
        private final List<ToolCallBuffer> toolCalls = new ArrayList<>();
        private ModelResponse.TokenUsage usage;
        private boolean done;

        /**
         * Parses one SSE payload into zero or more events.
         * <p>
         * Order matters: a chunk's content is emitted before any terminal event
         * derived from that same chunk.
         */
        Stream<StreamEvent> consume(String data) {
            if (done) {
                return Stream.empty();
            }
            try {
                JsonNode chunk = mapper.readTree(data);

                // A vendor may report an error mid-stream with HTTP 200 already sent.
                JsonNode errorNode = chunk.get("error");
                if (errorNode != null && !errorNode.isNull()) {
                    done = true;
                    String message = errorNode.path("message").asText("Streaming error");
                    return Stream.of(new StreamEvent.Error(message,
                            new ModelException(ModelException.ErrorCode.MODEL_ERROR, message)));
                }

                JsonNode choice = chunk.path("choices").path(0);
                ChatDelta delta = new ChatDelta(choice.path("delta"));
                captureUsage(chunk);

                List<StreamEvent> events = new ArrayList<>(2);

                // 1. Answer channel. Reasoning is parsed by ChatDelta but never
                //    appended here — it must not reach the answer.
                String contentDelta = delta.content();
                if (contentDelta != null) {
                    content.append(contentDelta);
                    events.add(new StreamEvent.ContentDelta(contentDelta));
                }

                // 2. Tool-call fragments, merged by index
                bufferToolCalls(delta.toolCalls());

                // 3. Terminal check LAST, so this chunk's payload is never lost
                JsonNode finishNode = choice.get("finish_reason");
                if (finishNode != null && !finishNode.isNull() && !finishNode.asText().isEmpty()) {
                    done = true;
                    events.add(new StreamEvent.Done(buildResponse(finishNode.asText())));
                }

                return events.isEmpty() ? Stream.empty() : events.stream();
            } catch (Exception e) {
                done = true;
                return Stream.of(new StreamEvent.Error("Failed to parse SSE chunk: " + data, e));
            }
        }

        private void captureUsage(JsonNode chunk) {
            JsonNode usageNode = chunk.get("usage");
            if (usageNode == null || usageNode.isNull()) {
                return;
            }
            usage = new ModelResponse.TokenUsage(
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0),
                    usageNode.path("total_tokens").asInt(0));
        }

        /**
         * Merges incremental tool-call deltas. Providers stream {@code arguments}
         * as JSON text split across chunks and identify the call by {@code index};
         * {@code id} and {@code name} usually arrive only in the first fragment.
         */
        private void bufferToolCalls(JsonNode array) {
            if (array == null) {
                return;
            }
            for (JsonNode tc : array) {
                int index = tc.path("index").asInt(0);
                while (toolCalls.size() <= index) {
                    toolCalls.add(new ToolCallBuffer());
                }
                ToolCallBuffer buffer = toolCalls.get(index);

                JsonNode id = tc.get("id");
                if (id != null && id.isTextual() && !id.asText().isEmpty()) {
                    buffer.id = id.asText();
                }
                JsonNode function = tc.path("function");
                JsonNode name = function.get("name");
                if (name != null && name.isTextual() && !name.asText().isEmpty()) {
                    buffer.name = name.asText();
                }
                JsonNode arguments = function.get("arguments");
                if (arguments != null && arguments.isTextual()) {
                    buffer.arguments.append(arguments.asText());
                }
            }
        }

        private ModelResponse buildResponse(String finishReason) {
            List<ToolCall> resolved = new ArrayList<>(toolCalls.size());
            for (ToolCallBuffer buffer : toolCalls) {
                ToolCall call = buffer.toToolCall();
                if (call != null) {
                    resolved.add(call);
                }
            }
            return new ModelResponse(
                    content.toString(),
                    resolved.isEmpty() ? null : resolved,
                    finishReason,
                    usage);
        }

        /**
         * Terminal event for streams that ended without {@code finish_reason}.
         * <p>
         * Evaluated only after the upstream lines are exhausted. Returns null
         * when a terminal event was already emitted, so the normal path stays
         * single-{@code Done}.
         *
         * @return a synthetic {@link StreamEvent.Done}, or null if not needed
         */
        StreamEvent terminalFallback() {
            if (done) {
                return null;
            }
            done = true;
            if (content.isEmpty() && toolCalls.isEmpty()) {
                // Nothing usable arrived. Surface it as an error rather than an
                // empty answer, otherwise callers persist a blank reply.
                String detail = "Stream produced no content";
                log.warn(detail);
                return new StreamEvent.Error(detail,
                        new ModelException(ModelException.ErrorCode.MODEL_ERROR, detail));
            }
            log.debug("Stream ended without finish_reason; synthesizing Done");
            return new StreamEvent.Done(buildResponse("stop"));
        }
    }

    /**
     * Mutable holder for a tool call assembled across SSE chunks.
     */
    private static final class ToolCallBuffer {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        ToolCall toToolCall() {
            if (name == null || name.isEmpty()) {
                return null;
            }
            JsonNode args;
            String raw = arguments.toString();
            if (raw.isBlank()) {
                args = mapper.createObjectNode();
            } else {
                try {
                    args = mapper.readTree(raw);
                } catch (Exception e) {
                    log.warn("Failed to parse streamed tool call arguments for '{}': {}", name, raw);
                    args = mapper.createObjectNode();
                }
            }
            return ToolCall.of(id != null ? id : "", name, args);
        }
    }

    // ============ Endpoint Flavor ============

    /**
     * Which OpenAI-compatible service we are talking to.
     * <p>
     * This exists for exactly one reason: to pick the reasoning request switch,
     * which is the one thing these endpoints genuinely disagree on. It is
     * deliberately <em>not</em> a strategy object — wire-format differences on
     * the response side are handled tolerantly by {@link ChatDelta}, which
     * needs to know nothing about vendors.
     * <p>
     * Unknown hosts fall through to {@link #GENERIC}, whose reasoning switch is
     * a no-op: guessing a vendor-specific field at an unknown server risks a
     * 400, whereas omitting it only means the model keeps its default.
     */
    public enum Flavor {
        OPENAI,
        ARK,
        QWEN,
        DEEPSEEK,
        OPENROUTER,
        /** Anthropic-compatible gateways fronted through the OpenAI protocol. */
        ANTHROPIC_STYLE,
        /** Ollama, vLLM, LM Studio, private gateways, and anything new. */
        GENERIC;

        /**
         * Detects the endpoint from its base URL. Explicit is better: pass a
         * {@code Flavor} to the constructor when fronting a vendor through a
         * custom domain.
         */
        static Flavor forBaseUrl(String baseUrl) {
            if (baseUrl == null || baseUrl.isBlank()) {
                return GENERIC;
            }
            String url = baseUrl.toLowerCase();
            if (url.contains("volces.com") || url.contains("volcengine")) {
                return ARK;
            }
            if (url.contains("dashscope") || url.contains("aliyuncs.com")) {
                return QWEN;
            }
            if (url.contains("deepseek.com")) {
                return DEEPSEEK;
            }
            if (url.contains("openrouter.ai")) {
                return OPENROUTER;
            }
            if (url.contains("anthropic.com")) {
                return ANTHROPIC_STYLE;
            }
            if (url.contains("api.openai.com") || url.contains("openai.azure.com")) {
                return OPENAI;
            }
            return GENERIC;
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

package io.github.qwzhang01.agent.model.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.ReasoningConfig;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.model.testsupport.MockApiServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for streaming against real-vendor SSE shapes.
 * <p>
 * The incident: Volcengine Ark's final chunk carries {@code content:""} AND
 * {@code finish_reason:"stop"} together, and reasoning models prefix the
 * stream with {@code reasoning_content} deltas. The old parser returned early
 * on the content branch, so finish_reason was never observed, Done was never
 * emitted, and the agent loop failed every turn with
 * "Stream ended without a Done event".
 */
class OpenAiStreamParsingTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    private MockApiServer api;

    @BeforeEach
    void setUp() throws Exception {
        api = new MockApiServer();
    }

    @AfterEach
    void tearDown() {
        api.close();
    }

    // ============ Helpers ============

    private static String chunk(String json) {
        return "data: " + json + "\n\n";
    }

    private static String done() {
        return "data: [DONE]\n\n";
    }

    private static ModelRequest chat(String text) {
        return ModelRequest.builder().addMessage(ChatMessage.user(text)).build();
    }

    private static List<StreamEvent> drain(OpenAiModelClient client, ModelRequest request) {
        try (var stream = client.stream(request)) {
            return stream.toList();
        }
    }

    private static StreamEvent.Done singleDone(List<StreamEvent> events) {
        List<StreamEvent.Done> dones = events.stream()
                .filter(e -> e instanceof StreamEvent.Done)
                .map(e -> (StreamEvent.Done) e)
                .toList();
        assertEquals(1, dones.size(), "exactly one Done expected, full events: " + events);
        return dones.get(0);
    }

    private static long countOf(List<StreamEvent> events, Class<?> type) {
        return events.stream().filter(type::isInstance).count();
    }

    private static OpenAiModelClient client(String base, String model,
                                            OpenAiModelClient.Flavor flavor,
                                            ReasoningConfig reasoning) {
        return new OpenAiModelClient(base, "test-key", model,
                Duration.ofSeconds(30), flavor, reasoning, null);
    }

    // ============ Ark (Volcengine) ============

    @Test
    void arkFinalChunkWithEmptyContentAndFinishReasonStillEmitsDone() {
        // Exact shape captured from a live doubao-seed-2-0-lite stream.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\",\"role\":\"assistant\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"你好\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"呀\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(
                api.baseUrl(), "test-key", "doubao-seed-2-0-lite-260428");

        List<StreamEvent> events = drain(client, chat("hi"));

        StreamEvent.Done done = singleDone(events);
        assertEquals("你好呀", done.finalResponse().content(),
                "content fully accumulated, reasoning excluded");
        assertEquals("stop", done.finalResponse().finishReason());
        assertEquals(0, countOf(events, StreamEvent.Error.class), "no error events expected");
    }

    @Test
    void arkDisabledReasoningSendsThinkingSwitch() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"好的\",\"role\":\"assistant\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        drain(client(api.baseUrl(), "doubao-seed-2-0-lite-260428",
                OpenAiModelClient.Flavor.ARK, ReasoningConfig.disabled()), chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertEquals("disabled", body.path("thinking").path("type").asText(),
                "Ark off-switch must be sent as thinking.type=disabled");
    }

    @Test
    void arkAutoReasoningSendsNoSwitch() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"hi\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        drain(client(api.baseUrl(), "doubao-seed-2-0-lite-260428",
                OpenAiModelClient.Flavor.ARK, null), chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertFalse(body.has("thinking"), "AUTO must not send any reasoning switch");
    }

    // ============ OpenAI classic ============

    @Test
    void openaiClassicShapeStillWorks() {
        // OpenAI's final chunk has an EMPTY delta object with finish_reason —
        // no content key at all. Both shapes must terminate the stream.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"Hel\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"lo\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        assertEquals("Hello", singleDone(drain(client, chat("hi"))).finalResponse().content());
    }

    @Test
    void openaiEnabledReasoningSendsReasoningEffort() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        drain(client(api.baseUrl(), "o3", OpenAiModelClient.Flavor.OPENAI,
                ReasoningConfig.enabled("high")), chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertEquals("high", body.path("reasoning_effort").asText());
    }

    // ============ Degenerate streams ============

    @Test
    void streamWithoutFinishReasonSynthesizesDone() {
        // Some gateways send [DONE] and close without ever sending finish_reason.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"partial answer\"},\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        StreamEvent.Done done = singleDone(drain(client, chat("hi")));
        assertEquals("partial answer", done.finalResponse().content());
        assertEquals("stop", done.finalResponse().finishReason());
    }

    @Test
    void emptyStreamSurfacesErrorNotBlankDone() {
        // Nothing usable arrived: better an explicit error than a blank answer
        // that would be persisted as the assistant reply.
        api.enqueue("/chat/completions", done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        List<StreamEvent> events = drain(client, chat("hi"));
        assertEquals(1, countOf(events, StreamEvent.Error.class),
                "empty stream must surface an Error event");
        assertEquals(0, countOf(events, StreamEvent.Done.class),
                "no Done should be synthesized for an empty stream");
    }

    @Test
    void reasoningOnlyStreamSurfacesError() {
        // Model thought but never answered: persisting "" would look successful.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"reasoning_content\":\"想来想去\"},\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        List<StreamEvent> events = drain(client, chat("hi"));
        assertEquals(1, countOf(events, StreamEvent.Error.class));
        assertEquals(0, countOf(events, StreamEvent.Done.class));
    }

    // ============ Endpoint flavor detection ============

    @Test
    void flavorAutoDetectedFromBaseUrl() {
        assertEquals(OpenAiModelClient.Flavor.ARK,
                OpenAiModelClient.Flavor.forBaseUrl("https://ark.cn-beijing.volces.com/api/v3"));
        assertEquals(OpenAiModelClient.Flavor.QWEN,
                OpenAiModelClient.Flavor.forBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"));
        assertEquals(OpenAiModelClient.Flavor.DEEPSEEK,
                OpenAiModelClient.Flavor.forBaseUrl("https://api.deepseek.com/v1"));
        assertEquals(OpenAiModelClient.Flavor.OPENROUTER,
                OpenAiModelClient.Flavor.forBaseUrl("https://openrouter.ai/api/v1"));
        assertEquals(OpenAiModelClient.Flavor.OPENAI,
                OpenAiModelClient.Flavor.forBaseUrl("https://api.openai.com/v1"));
        assertEquals(OpenAiModelClient.Flavor.GENERIC,
                OpenAiModelClient.Flavor.forBaseUrl("http://localhost:11434/v1"));
        assertEquals(OpenAiModelClient.Flavor.GENERIC,
                OpenAiModelClient.Flavor.forBaseUrl(null));
    }

    @Test
    void qwenDialectSendsEnableThinkingFalse() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"好\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        drain(client(api.baseUrl(), "qwen3-max",
                OpenAiModelClient.Flavor.QWEN, ReasoningConfig.disabled()), chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertFalse(body.path("enable_thinking").asBoolean(true),
                "Qwen off-switch must be sent as enable_thinking=false");
    }

    @Test
    void unknownEndpointSendsNoReasoningSwitch() throws Exception {
        // GENERIC must not guess: an unknown field risks a 400, whereas
        // omitting the switch only means the model keeps its default.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        drain(client(api.baseUrl(), "local-model",
                OpenAiModelClient.Flavor.GENERIC, ReasoningConfig.disabled()), chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertFalse(body.has("thinking"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("reasoning"));
    }

    // ============ Reasoning channel tolerance ============

    @Test
    void reasoningParsedFromEveryKnownFieldName() {
        // Each vendor spells the channel differently; none may reach content.
        assertReasoningFieldExcluded("reasoning_content");
        assertReasoningFieldExcluded("reasoning");
        assertReasoningFieldExcluded("thinking");
    }

    private void assertReasoningFieldExcluded(String field) {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"" + field + "\":" + quote("scratch") + "},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"answer\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "m");
        assertEquals("answer", singleDone(drain(client, chat("hi"))).finalResponse().content(),
                "reasoning field '" + field + "' must not leak into the answer");
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    @Test
    void nestedReasoningObjectIsUnwrappedAndExcluded() {
        // Anthropic-style proxies send {"thinking": {"text": "..."}}.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"thinking\":{\"text\":\"scratch\"}},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"content\":\"answer\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "m");
        assertEquals("answer", singleDone(drain(client, chat("hi"))).finalResponse().content());
    }

    // ============ extraBody escape hatch ============

    @Test
    void extraBodyMergesVendorSpecificFields() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "claude",
                Duration.ofSeconds(30), OpenAiModelClient.Flavor.GENERIC, null,
                Map.of("thinking", Map.of("budget_tokens", 8000), "top_k", 40));

        drain(client, chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertEquals(8000, body.path("thinking").path("budget_tokens").asInt());
        assertEquals(40, body.path("top_k").asInt());
    }

    @Test
    void extraBodyCannotOverrideStandardFields() throws Exception {
        // Protocol integrity: a vendor override must not corrupt standard fields.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini",
                Duration.ofSeconds(30), OpenAiModelClient.Flavor.OPENAI, null,
                Map.of("model", "hijacked", "stream", false));

        drain(client, chat("hi"));

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertEquals("gpt-4o-mini", body.path("model").asText(),
                "extraBody must not override the model");
        assertTrue(body.path("stream").asBoolean(), "extraBody must not override the stream flag");
    }

    // ============ Non-streaming ============

    @Test
    void nonStreamingReasoningStaysOutOfContent() {
        // DeepSeek-R1 style response: message carries both reasoning_content
        // and content; only content is the answer.
        api.enqueue("/chat/completions",
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"答案是42\","
                        + "\"reasoning_content\":\"让我想想...\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}");

        OpenAiModelClient client = client(api.baseUrl(), "deepseek-reasoner",
                OpenAiModelClient.Flavor.DEEPSEEK, null);

        ModelResponse response = client.chat(chat("终极问题"));
        assertEquals("答案是42", response.content(),
                "reasoning_content must never leak into the answer");
        assertNotNull(response.usage());
    }

    @Test
    void midStreamErrorSurfacesAsErrorEvent() {
        // HTTP 200 already sent, then an error object arrives mid-stream.
        api.enqueue("/chat/completions",
                chunk("{\"error\":{\"message\":\"model overloaded\",\"type\":\"server_error\"}}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        List<StreamEvent> events = drain(client, chat("hi"));
        assertEquals(1, countOf(events, StreamEvent.Error.class));
        assertEquals("model overloaded", ((StreamEvent.Error) events.get(0)).message());
        assertEquals(0, countOf(events, StreamEvent.Done.class));
    }

    @Test
    void requestLevelReasoningOverridesClientDefault() throws Exception {
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"content\":\"ok\"},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = client(api.baseUrl(), "doubao-seed-2-0-lite-260428",
                OpenAiModelClient.Flavor.ARK, ReasoningConfig.disabled());

        // Client says disabled, request says AUTO -> no switch sent
        drain(client, ModelRequest.builder()
                .addMessage(ChatMessage.user("hi"))
                .reasoning(ReasoningConfig.auto())
                .build());

        JsonNode body = mapper.readTree(api.capturedBody("/chat/completions"));
        assertFalse(body.has("thinking"), "request-level AUTO must override client default");
    }

    // ============ Tool calls across chunks ============

    @Test
    void streamedToolCallArgumentsMergedByIndex() {
        // Arguments split across chunks; id/name only in the first fragment.
        api.enqueue("/chat/completions",
                chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\","
                        + "\"arguments\":\"{\\\"city\\\":\"}}]},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"北京\\\"}\"}}]},\"index\":0}]}")
                + chunk("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\",\"index\":0}]}")
                + done());

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        StreamEvent.Done done = singleDone(drain(client, chat("北京天气")));
        var toolCalls = done.finalResponse().toolCalls();
        assertNotNull(toolCalls, "tool calls must be accumulated into the Done response");
        assertEquals(1, toolCalls.size());
        assertEquals("get_weather", toolCalls.get(0).name());
        assertEquals("call_1", toolCalls.get(0).id());
        assertEquals("北京", toolCalls.get(0).arguments().path("city").asText());
        assertEquals("tool_calls", done.finalResponse().finishReason());
    }

    @Test
    void sseDataLineWithoutSpaceIsAccepted() {
        // Strictly-valid SSE requires "data: "; some servers omit the space.
        api.enqueue("/chat/completions",
                "data:{\"choices\":[{\"delta\":{\"content\":\"compact\"},\"index\":0}]}\n\n"
                + "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\",\"index\":0}]}\n\n"
                + "data:[DONE]\n\n");

        OpenAiModelClient client = new OpenAiModelClient(api.baseUrl(), "test-key", "gpt-4o-mini");

        assertEquals("compact", singleDone(drain(client, chat("hi"))).finalResponse().content());
    }
}

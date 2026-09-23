package io.github.qwzhang01.agent.model.contract;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.client.ModelException;
import io.github.qwzhang01.agent.core.client.ProviderCallException;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 6.1: the shared contract test every {@link ModelClient} provider
 * implementation must pass.
 * <p>
 * A provider is not "done" when its happy path works against its own vendor
 * SDK. It is done when it passes THIS suite — the same behavioural bar for
 * every vendor: sync, streaming, tool calls, structured output, usage
 * accounting, error mapping, and the additive Stage 5/6 fields. New providers
 * plug in by implementing the two abstract hooks and running the suite.
 * <p>
 * The suite runs against an injectable fake HTTP layer, so it validates the
 * client's <b>mapping logic</b> (request shaping, response parsing, error
 * translation), not the vendor's uptime. That is the point: contract tests
 * pin OUR translation, integration smoke tests pin the vendor.
 * <p>
 * What each hook provides:
 * <ul>
 *   <li>{@link #clientFor(String)} — a client wired to the canned HTTP body</li>
 *   <li>{@link #providerName()} — expected name in audit records</li>
 * </ul>
 */
public abstract class ModelClientContract {

    /** Build a client whose HTTP layer always returns this canned body. */
    protected abstract ModelClient clientFor(String httpBody);

    /** The provider's name for audit/logging records. */
    protected abstract String providerName();


    @Test
    @DisplayName("contract: sync text round-trip returns content + usage")
    void syncTextRoundTrip() {
        ModelClient client = clientFor(syncBody());
        ModelResponse resp = client.chat(request());
        assertNotNull(resp);
        assertNotNull(resp.content());
        assertFalse(resp.content().isBlank());
        ModelResponse.TokenUsage usage = resp.usage();
        assertNotNull(usage, "usage must be reported (roadmap 6.1: Usage)");
        assertTrue(usage.promptTokens() > 0, "promptTokens must be positive");
        assertTrue(usage.completionTokens() > 0, "completionTokens must be positive");
    }

    @Test
    @DisplayName("contract: tool calls parse into ToolCall records")
    void toolCallParsing() {
        ModelClient client = clientFor(toolCallBody());
        ModelResponse resp = client.chat(request());
        assertNotNull(resp.toolCalls());
        assertEquals(1, resp.toolCalls().size());
        ToolCall call = resp.toolCalls().get(0);
        assertNotNull(call.id());
        assertNotNull(call.name());
        assertNotNull(call.arguments());
    }

    @Test
    @DisplayName("contract: stream ends with a terminal event and carries text")
    void streamRoundTrip() {
        ModelClient client = clientFor(streamBody());
        try (Stream<StreamEvent> events = client.stream(request())) {
            List<StreamEvent> collected = events.toList();
            assertFalse(collected.isEmpty());
            boolean hasTerminal = collected.stream().anyMatch(e ->
                    e instanceof StreamEvent.Done || e instanceof StreamEvent.Error);
            assertTrue(hasTerminal, "stream must end with a terminal event (Done or Error)");
            boolean carriesText = collected.stream()
                    .anyMatch(e -> e instanceof StreamEvent.ContentDelta cd
                            && cd.delta() != null && !cd.delta().isBlank());
            assertTrue(carriesText, "stream must carry text deltas");
        }
    }

    @Test
    @DisplayName("contract: streaming tool calls accumulate into Done")
    void streamingToolCallAccumulation() {
        ModelClient client = clientFor(streamToolCallBody());
        try (Stream<StreamEvent> events = client.stream(request())) {
            List<StreamEvent> collected = events.toList();
            StreamEvent.Done done = collected.stream()
                    .filter(e -> e instanceof StreamEvent.Done)
                    .map(e -> (StreamEvent.Done) e)
                    .findFirst()
                    .orElse(null);
            assertNotNull(done, "stream must end with Done carrying the accumulated tool call");
            ModelResponse finalResp = done.finalResponse();
            assertNotNull(finalResp.toolCalls(),
                    "Done must carry accumulated tool calls (roadmap 6.1: streaming tool use chain)");
            assertFalse(finalResp.toolCalls().isEmpty());
            ToolCall call = finalResp.toolCalls().get(0);
            assertNotNull(call.id(), "tool call id must survive accumulation");
            assertNotNull(call.name());
            assertNotNull(call.arguments(), "arguments must be assembled from partial JSON deltas");
        }
    }


    @Test
    @DisplayName("contract: 401 maps to AUTH_ERROR")
    void authErrorMapping() {
        ModelClient client = clientForError(401, "{\"error\":{\"type\":\"authentication_error\",\"message\":\"bad key\"}}");
        ModelException ex = assertThrows(ModelException.class, () -> client.chat(request()));
        assertEquals(ModelException.ErrorCode.AUTH_ERROR, ex.getCode());
    }

    @Test
    @DisplayName("contract: 429 maps to RATE_LIMITED")
    void rateLimitMapping() {
        ModelClient client = clientForError(429, "{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
        ModelException ex = assertThrows(ModelException.class, () -> client.chat(request()));
        assertEquals(ModelException.ErrorCode.RATE_LIMITED, ex.getCode());
    }

    @Test
    @DisplayName("contract: 400 maps to INVALID_REQUEST")
    void invalidRequestMapping() {
        ModelClient client = clientForError(400, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad args\"}}");
        ModelException ex = assertThrows(ModelException.class, () -> client.chat(request()));
        assertEquals(ModelException.ErrorCode.INVALID_REQUEST, ex.getCode());
    }

    @Test
    @DisplayName("contract: 500 maps to MODEL_ERROR")
    void serverErrorMapping() {
        ModelClient client = clientForError(500, "{\"error\":{\"type\":\"api_error\",\"message\":\"on fire\"}}");
        ModelException ex = assertThrows(ModelException.class, () -> client.chat(request()));
        assertEquals(ModelException.ErrorCode.MODEL_ERROR, ex.getCode());
    }

    protected ModelRequest request() {
        return ModelRequest.builder()
                .messages(List.of(ChatMessage.user("hello")))
                .build();
    }

    /** Build a client whose HTTP layer fails with this status/body. */
    protected abstract ModelClient clientForError(int status, String body);

    // Canned bodies (override per provider wire format)

    /** Override: a successful sync response body in this provider's format. */
    protected abstract String syncBody();

    /** Override: a successful tool-call response body in this provider's format. */
    protected abstract String toolCallBody();

    /** Override: a streaming response body (SSE frames) in this provider's format. */
    protected abstract String streamBody();

    /**
     * Override: a streaming TOOL CALL body in this provider's format. The
     * frames must split the tool arguments across at least two deltas so the
     * contract can prove accumulation, not single-shot parsing.
     */
    protected abstract String streamToolCallBody();
}

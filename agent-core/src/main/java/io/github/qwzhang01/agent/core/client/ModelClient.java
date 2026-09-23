package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.run.RunContext;

import java.util.stream.Stream;

/**
 * Unified interface for calling LLM providers.
 * <p>
 * Design principle: Agent code depends on this interface, never on a specific
 * provider SDK. Swapping OpenAI for a local model should not change Agent logic.
 * <p>
 * covers:
 * - {@link #chat} synchronous call
 * - {@link #stream} streaming call
 * - tool calling (conveyed via ModelRequest.tools / ModelResponse.toolCalls)
 * - Timeout / Retry / Fallback (via decorators: {@link TimeoutModelClient},
 * {@link RetryModelClient}, {@link FallbackModelClient})
 * - Structured Output (via ModelRequest.responseFormat + {@link StructuredOutputModelClient})
 */
public interface ModelClient {

    /**
     * Synchronous chat completion.
     *
     * @param request model request
     * @return model response
     * @throws io.github.qwzhang01.agent.core.client.ModelException if the call fails
     */
    ModelResponse chat(ModelRequest request);

    /**
     * Streaming chat completion.
     * <p>
     * Returns a Stream of events. The caller must consume the stream
     * (e.g. via try-with-resources or terminal operation).
     *
     * @param request model request (stream flag is ignored, always streams)
     * @return stream of events, ending with Done or Error
     */
    Stream<StreamEvent> stream(ModelRequest request);


    /**
     * Synchronous chat completion with the run context .
     * <p>
     * Default: delegate to the legacy method. Implementations that need
     * run-scoped billing, budget consumption or trace correlation override
     * this (e.g. 's metered client). The context is read-only for
     * the client; it must never be re-created or mutated downstream.
     *
     * @param request model request
     * @param ctx the run context (may be null on the legacy path)
     * @return model response
     */
    default ModelResponse chat(ModelRequest request, RunContext ctx) {
        return chat(request);
    }

    /**
     * Streaming chat completion with the run context. See
     * {@link #chat(ModelRequest, RunContext)} for semantics.
     */
    default Stream<StreamEvent> stream(ModelRequest request, RunContext ctx) {
        return stream(request);
    }
}

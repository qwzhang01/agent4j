package com.seven.agent.core.client;

import com.seven.agent.core.model.ModelRequest;
import com.seven.agent.core.model.ModelResponse;
import com.seven.agent.core.model.StreamEvent;
import java.util.stream.Stream;

/**
 * Unified interface for calling LLM providers.
 * <p>
 * Design principle: Agent code depends on this interface, never on a specific
 * provider SDK. Swapping OpenAI for a local model should not change Agent logic.
 * <p>
 * Stage 1 covers:
 * - {@link #chat} synchronous call
 * - {@link #stream} streaming call
 * - tool calling (conveyed via ModelRequest.tools / ModelResponse.toolCalls)
 * - Timeout / Retry / Fallback (via decorators: {@link TimeoutModelClient},
 *   {@link RetryModelClient}, {@link FallbackModelClient})
 * - Structured Output (via ModelRequest.responseFormat + {@link StructuredOutputModelClient})
 */
public interface ModelClient {

    /**
     * Synchronous chat completion.
     *
     * @param request model request
     * @return model response
     * @throws com.seven.agent.core.client.ModelException if the call fails
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
}

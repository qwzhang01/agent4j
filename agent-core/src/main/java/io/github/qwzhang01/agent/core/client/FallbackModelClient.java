package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Stream;

/**
 * Decorator that provides fallback to a secondary ModelClient when the primary fails.
 * <p>
 * Use case: primary = expensive GPT-4, fallback = cheaper GPT-4o-mini.
 * Or: primary = cloud model, fallback = local model.
 * <p>
 * Fallback triggers when:
 * - Primary throws ModelException (any error)
 * - Primary response has finishReason "error"
 * <p>
 * Does NOT fallback on:
 * - Successful responses (even if quality is low - that's a routing concern, stage 18)
 */
public class FallbackModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(FallbackModelClient.class);

    private final ModelClient primary;
    private final List<ModelClient> fallbacks;

    public FallbackModelClient(ModelClient primary, ModelClient... fallbacks) {
        this.primary = primary;
        this.fallbacks = List.of(fallbacks);
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        try {
            ModelResponse response = primary.chat(request);
            if (!"error".equals(response.finishReason())) {
                return response;
            }
            log.warn("Primary returned error response, falling back");
            return fallbackChat(request, 0);
        } catch (ModelException e) {
            log.warn("Primary failed ({}), falling back: {}", e.getCode(), e.getMessage());
            return fallbackChat(request, 0);
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        try {
            return primary.stream(request);
        } catch (ModelException e) {
            log.warn("Primary stream failed ({}), falling back: {}", e.getCode(), e.getMessage());
            return fallbackStream(request, 0);
        }
    }

    // ============ Private Helpers ============

    private ModelResponse fallbackChat(ModelRequest request, int index) {
        if (index >= fallbacks.size()) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "All fallback clients exhausted");
        }
        ModelClient fallback = fallbacks.get(index);
        try {
            ModelResponse response = fallback.chat(request);
            if (!"error".equals(response.finishReason())) {
                return response;
            }
            log.warn("Fallback {} returned error, trying next", index);
            return fallbackChat(request, index + 1);
        } catch (ModelException e) {
            log.warn("Fallback {} failed ({}), trying next", index, e.getCode());
            return fallbackChat(request, index + 1);
        }
    }

    private Stream<StreamEvent> fallbackStream(ModelRequest request, int index) {
        if (index >= fallbacks.size()) {
            throw new ModelException(ModelException.ErrorCode.MODEL_ERROR,
                    "All fallback stream clients exhausted");
        }
        try {
            return fallbacks.get(index).stream(request);
        } catch (ModelException e) {
            log.warn("Fallback stream {} failed ({}), trying next", index, e.getCode());
            return fallbackStream(request, index + 1);
        }
    }
}

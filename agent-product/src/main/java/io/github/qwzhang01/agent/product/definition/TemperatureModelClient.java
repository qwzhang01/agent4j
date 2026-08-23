package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;

import java.util.stream.Stream;

/**
 * Decorator that injects the definition's default temperature into requests that do
 * not carry one (Stage 13 M13.1).
 * <p>
 * Why a decorator and not an AgentConfig field: {@code AgentConfig} has no
 * temperature slot and {@code ReActAgentLoop} never sets one - the sampling
 * parameter lives in {@link ModelRequest}. Rather than touching agent-core
 * (assembly-stage discipline), the binder wraps the assembled client chain:
 * {@code Temperature(Fallback(primary, fallbacks...))} - the same decorator
 * philosophy as Retry/Timeout/Fallback (Stage 1).
 * <p>
 * Semantics: an explicitly set request temperature wins; the definition value is a
 * <b>default</b>, not an override.
 */
public final class TemperatureModelClient implements ModelClient {

    private final ModelClient delegate;
    private final double temperature;

    public TemperatureModelClient(ModelClient delegate, double temperature) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate must not be null");
        this.temperature = temperature;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return delegate.chat(applyDefault(request));
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        return delegate.stream(applyDefault(request));
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private ModelRequest applyDefault(ModelRequest request) {
        if (request.temperature() != null) {
            return request; // explicit wins
        }
        return ModelRequest.builder()
                .model(request.model())
                .messages(request.messages())
                .tools(request.tools())
                .temperature(temperature)
                .maxTokens(request.maxTokens())
                .stream(request.stream())
                .responseFormat(request.responseFormat())
                .build();
    }
}

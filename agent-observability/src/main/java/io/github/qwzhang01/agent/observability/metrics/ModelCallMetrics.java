package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.model.ModelResponse;

/**
 * Operational facts of one model call, captured at the ModelClient boundary
 * (Stage 18 D2: metrics live at the boundary, not in the loop path).
 *
 * @param model            model identifier from the request (e.g. "gpt-4o")
 * @param latencyMs        wall-clock latency of the call, measured around the delegate
 * @param promptTokens     prompt tokens reported by the model (0 if usage not reported)
 * @param completionTokens completion tokens reported by the model (0 if usage not reported)
 * @param totalTokens      total tokens reported by the model (0 if usage not reported)
 * @param finishReason     finish reason from the response (null on failure)
 * @param error            failure description, null on success - the delegate's
 *                         exception is recorded and rethrown, never swallowed here
 */
public record ModelCallMetrics(
        String model,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        String finishReason,
        String error) {

    public boolean success() {
        return error == null;
    }

    /**
     * From a successful (returned) response; usage not reported by the provider
     * becomes 0 - the caller cannot distinguish "free" from "unreported", and
     * honest accounting treats unreported as zero until M18.2 pricing refuses
     * to guess (fail-loud on missing pricing rows).
     */
    static ModelCallMetrics from(String model, ModelResponse response, long latencyMs) {
        ModelResponse.TokenUsage usage = response.usage();
        return new ModelCallMetrics(
                model,
                latencyMs,
                usage != null ? usage.promptTokens() : 0,
                usage != null ? usage.completionTokens() : 0,
                usage != null ? usage.totalTokens() : 0,
                response.finishReason(),
                null);
    }

    /** From a failed call - no tokens, no finish reason, the error text stays. */
    static ModelCallMetrics failure(String model, long latencyMs, String error) {
        return new ModelCallMetrics(model, latencyMs, 0, 0, 0, null, error);
    }
}

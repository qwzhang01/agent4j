package io.github.qwzhang01.agent.core.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Response from a model provider.
 *
 * @param content      text output from the model (may be empty if only tool calls)
 * @param toolCalls    tool calls requested by the model (null if none)
 * @param finishReason why the model stopped: "stop", "tool_calls", "length", "error"
 * @param usage        token usage stats (null if not reported)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ModelResponse(
        String content,
        List<ToolCall> toolCalls,
        String finishReason,
        TokenUsage usage
) {
    public static ModelResponse text(String content) {
        return new ModelResponse(content, null, "stop", null);
    }

    public static ModelResponse toolCalls(List<ToolCall> calls) {
        return new ModelResponse(null, calls, "tool_calls", null);
    }

    // ============ Factory ============

    public static ModelResponse error(String message) {
        return new ModelResponse(null, null, "error", null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isFinished() {
        return !"tool_calls".equals(finishReason);
    }

    // ============ Nested ============

    /**
     * Token usage as billed by the provider.
     * <p>
     * Accounting semantics (E3, decision 26): {@code promptTokens} is the FULL
     * billed prompt size including any cache-hit portion; {@code cachedTokens}
     * is the subset served from the prompt cache (KV cache). A provider that
     * does not report cache detail leaves it 0 - consumers cannot distinguish
     * "no cache hit" from "not reported", which is the same honest-zero
     * discipline as the other fields. Anthropic clients fold
     * {@code cache_read + cache_creation} into {@code promptTokens} and report
     * {@code cache_read} as {@code cachedTokens} so every provider maps onto
     * one billing shape: {@code cost = uncached * base + cached * read}.
     * <p>
     * Normalized: negative cachedTokens becomes 0; cachedTokens never exceeds
     * promptTokens (a cache hit larger than the prompt is a provider bug we
     * refuse to propagate downstream).
     *
     * @param promptTokens     full billed prompt tokens (uncached + cached)
     * @param completionTokens completion tokens
     * @param totalTokens      total tokens as reported
     * @param cachedTokens     prompt tokens served from cache (0 = none/unreported)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TokenUsage(int promptTokens, int completionTokens, int totalTokens, int cachedTokens) {

        public TokenUsage {
            if (cachedTokens < 0) {
                cachedTokens = 0;
            }
            if (cachedTokens > promptTokens) {
                cachedTokens = promptTokens;
            }
        }

        /**
         * Three-arg constructor kept for source compatibility with the
         * pre-E3 signature (no cache detail: cachedTokens = 0).
         */
        public TokenUsage(int promptTokens, int completionTokens, int totalTokens) {
            this(promptTokens, completionTokens, totalTokens, 0);
        }
    }
}

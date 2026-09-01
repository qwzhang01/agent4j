package io.github.qwzhang01.agent.core.model;

/**
 * Provider-neutral reasoning ("thinking") intent.
 * <p>
 * Reasoning models emit their chain-of-thought in a channel separate from the
 * answer. Every vendor spells the request switch differently, so this type
 * carries only the <em>canonical intent</em> each {@code ModelClient} can
 * translate into its own wire format:
 *
 * <table border="1">
 *   <caption>How {@link Mode} maps onto each provider's switch</caption>
 *   <tr><th>Provider</th><th>ENABLED</th><th>DISABLED</th></tr>
 *   <tr><td>Volcengine Ark</td><td>{@code "thinking":{"type":"enabled"}}</td>
 *       <td>{@code "thinking":{"type":"disabled"}}</td></tr>
 *   <tr><td>Anthropic</td><td>{@code "thinking":{"type":"enabled"}}</td>
 *       <td>{@code "thinking":{"type":"disabled"}}</td></tr>
 *   <tr><td>Qwen / DashScope</td><td>{@code "enable_thinking":true}</td>
 *       <td>{@code "enable_thinking":false}</td></tr>
 *   <tr><td>OpenAI o-series</td><td>{@code "reasoning_effort":"medium"}</td>
 *       <td>(no off-switch — reported via a warning)</td></tr>
 *   <tr><td>DeepSeek</td><td>(always on)</td><td>(no off-switch — warning)</td></tr>
 * </table>
 *
 * <p>
 * This type deliberately stays small. Vendor-specific knobs that only one
 * provider understands (Anthropic's {@code budget_tokens}, OpenAI's response
 * {@code include} list, ...) do <strong>not</strong> belong here — pass them
 * through the client's {@code extraBody} escape hatch instead. Keeping the
 * canonical surface narrow is what lets it survive contact with the next
 * vendor without another core change.
 *
 * <p>
 * Reasoning output is always parsed and kept out of the answer, regardless of
 * these settings: a model that thinks without being asked must not corrupt
 * {@link ModelResponse#content()}.
 *
 * @param mode   whether to ask the provider to think
 * @param effort effort hint ({@code "low"} / {@code "medium"} / {@code "high"});
 *               {@code null} for provider default. Honored only by providers
 *               that accept an effort level — others log a warning.
 */
public record ReasoningConfig(
        Mode mode,
        String effort
) {

    /**
     * Whether the provider should produce reasoning.
     */
    public enum Mode {
        /**
         * Send no reasoning switch — inherit the provider/model default.
         * Reasoning output is still parsed correctly if it arrives.
         */
        AUTO,

        /**
         * Explicitly ask the provider to think.
         */
        ENABLED,

        /**
         * Explicitly ask the provider not to think. Providers without an
         * off-switch (OpenAI o-series, DeepSeek) log a warning; their reasoning
         * output is still kept out of the answer.
         */
        DISABLED
    }

    // ============ Compact Constructor ============

    public ReasoningConfig {
        if (mode == null) {
            mode = Mode.AUTO;
        }
    }

    // ============ Factory Methods ============

    /**
     * Inherit the provider default. This is the framework default when no
     * config is supplied.
     */
    public static ReasoningConfig auto() {
        return new ReasoningConfig(Mode.AUTO, null);
    }

    /**
     * Turn reasoning off where the provider supports it.
     */
    public static ReasoningConfig disabled() {
        return new ReasoningConfig(Mode.DISABLED, null);
    }

    /**
     * Turn reasoning on at the provider's default effort.
     */
    public static ReasoningConfig enabled() {
        return new ReasoningConfig(Mode.ENABLED, null);
    }

    /**
     * Turn reasoning on at the given effort ({@code low} / {@code medium} / {@code high}).
     */
    public static ReasoningConfig enabled(String effort) {
        return new ReasoningConfig(Mode.ENABLED, effort);
    }

    // ============ Derived Accessors ============

    public boolean isDisabled() {
        return mode == Mode.DISABLED;
    }

    public boolean isEnabled() {
        return mode == Mode.ENABLED;
    }

    public boolean isAuto() {
        return mode == Mode.AUTO;
    }
}

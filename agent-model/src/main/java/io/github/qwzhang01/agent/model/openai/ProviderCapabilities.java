package io.github.qwzhang01.agent.model.openai;

import java.util.Set;

/**
 *  explicit capability declaration for OpenAI-compatible vendors.
 * <p>
 * Roadmap: "对 OpenAI-compatible 的不同厂商 extra body 和响应差异做
 * capability 声明" — vendors behind one protocol still disagree on the
 * edges. {@link OpenAiModelClient.Flavor} already picks each vendor's
 * <em>request switches</em> (the reasoning field is the big one); this
 * class exposes the rest of the disagreement surface as queryable facts,
 * so callers and diagnostics can ask "what does THIS endpoint actually
 * support?" instead of finding out from a 400.
 * <p>
 * The declaration is static per flavor — the honest reading of what the
 * client itself encodes. It is NOT a live probe (no vendor call); a live
 * probe belongs to a future health-check, not to a capability snapshot.
 * <b>Usage / JSON mode:</b> OPENAI supports {@code response_format:
 * json_schema} natively; others are coerced to prompt-instruction mode by
 * the client, and callers deserve to know that.
 */
public record ProviderCapabilities(OpenAiModelClient.Flavor flavor,
                                   boolean nativeJsonMode,
                                   boolean nativeReasoningSwitch,
                                   String reasoningField,
                                   boolean parsesReasoningContent,
                                   boolean strictExtraBodyEscapeHatch) {

    private static final Set<OpenAiModelClient.Flavor> NATIVE_JSON = Set.of(
            OpenAiModelClient.Flavor.OPENAI, OpenAiModelClient.Flavor.OPENROUTER);

    private static final Set<OpenAiModelClient.Flavor> PARSES_REASONING = Set.of(
            OpenAiModelClient.Flavor.OPENAI, OpenAiModelClient.Flavor.DEEPSEEK,
            OpenAiModelClient.Flavor.OPENROUTER);

    /**
     * The static declaration for a flavor. Every branch below mirrors a
     * real branch in {@link OpenAiModelClient}: if that code moves, this
     * table moves with it (tests pin the mapping).
     */
    public static ProviderCapabilities forFlavor(OpenAiModelClient.Flavor flavor) {
        boolean nativeJson = NATIVE_JSON.contains(flavor);
        boolean strictEscape = true; // extraBody merges, standard fields win on collision
        return switch (flavor) {
            case OPENAI -> new ProviderCapabilities(flavor, nativeJson, true,
                    "reasoning_effort", true, strictEscape);
            case ARK, ANTHROPIC_STYLE -> new ProviderCapabilities(flavor, nativeJson, true,
                    "thinking", false, strictEscape);
            case QWEN -> new ProviderCapabilities(flavor, nativeJson, true,
                    "enable_thinking", false, strictEscape);
            case OPENROUTER -> new ProviderCapabilities(flavor, nativeJson, true,
                    "reasoning", true, strictEscape);
            case DEEPSEEK, GENERIC -> new ProviderCapabilities(flavor, nativeJson, false,
                    null, true, strictEscape);
        };
    }
}

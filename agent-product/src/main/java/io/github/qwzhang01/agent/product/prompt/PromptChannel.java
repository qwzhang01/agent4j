package io.github.qwzhang01.agent.product.prompt;

/**
 * Release channels for prompts (Stage 13 M13.4, D4).
 * <p>
 * v1 has exactly two channels. Canary serves the tenants the operator routes
 * to it (tenant overrides); everyone else stays on stable. Percentage-based
 * canary requires sticky sessions and is v2.
 */
public final class PromptChannel {

    public static final String STABLE = "stable";
    public static final String CANARY = "canary";

    private PromptChannel() {
    }

    /**
     * @throws IllegalArgumentException if the channel is not stable/canary
     */
    public static void requireValid(String channel) {
        if (!STABLE.equals(channel) && !CANARY.equals(channel)) {
            throw new IllegalArgumentException(
                    "channel must be '" + STABLE + "' or '" + CANARY + "', got: " + channel);
        }
    }
}

package io.github.qwzhang01.agent.product.definition;

import java.util.Objects;

/**
 * A reference to a managed prompt (Stage 13 M13.4, D4: prompt as asset).
 * <pre>{@code
 * persona:
 *   promptRef: { name: support-system, channel: stable }
 * }</pre>
 * The DEFINITION may declare a channel; a tenant override (canary routing)
 * still wins at resolve time.
 *
 * @param name    managed prompt name (resolved via PromptManager)
 * @param channel release channel the definition asks for, null = stable
 */
public record PromptRef(String name, String channel) {

    public PromptRef {
        Objects.requireNonNull(name, "prompt name must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("promptRef.name must not be blank");
        }
    }
}

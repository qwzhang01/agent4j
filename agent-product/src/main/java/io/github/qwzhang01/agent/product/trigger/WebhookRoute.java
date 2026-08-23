package io.github.qwzhang01.agent.product.trigger;

import java.util.Objects;

/**
 * A webhook routing rule (Stage 13 M13.5, D8): which agent handles which
 * external source, and how the payload becomes the agent's input.
 *
 * @param source          external source identifier (e.g. "github", "alerting")
 * @param agentName       target agent (looked up in the AgentRegistry)
 * @param payloadTemplate optional {@code {$.path}} template; null = raw JSON
 * @param secret          HMAC-SHA256 signing secret (resolve ${env:...} in the
 *                        assembly layer BEFORE constructing the route)
 */
public record WebhookRoute(String source, String agentName, String payloadTemplate, String secret) {

    public WebhookRoute {
        requireText(source, "source");
        requireText(agentName, "agentName");
        requireText(secret, "secret (routes are HMAC-verified, no anonymous webhooks in v1)");
    }

    private static void requireText(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("WebhookRoute " + what + " must not be blank");
        }
        Objects.requireNonNull(value, what + " must not be null");
    }
}

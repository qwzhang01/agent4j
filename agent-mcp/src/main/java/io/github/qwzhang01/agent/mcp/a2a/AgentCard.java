package io.github.qwzhang01.agent.mcp.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

/**
 * Agent capability declaration (A2A protocol, Stage 10 v1).
 * <p>
 * Like a business card for an Agent: what it can do, how to reach it.
 * Sent during agent-to-agent discovery so other agents know what you offer.
 *
 * @param name         Agent's display name
 * @param description  What this Agent does
 * @param skills       List of capabilities (e.g. ["code-review", "deployment"])
 * @param endpoint     How to reach this Agent (URL or identifier)
 * @param version      Protocol version
 */
public record AgentCard(
        String name,
        String description,
        List<String> skills,
        String endpoint,
        String version
) {
    public AgentCard {
        Objects.requireNonNull(name, "name must not be null");
    }
}

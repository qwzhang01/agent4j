package io.github.qwzhang01.agent.mcp.a2a;

import java.util.List;
import java.util.Objects;

/**
 * Agent capability declaration (A2A protocol).
 * <p>
 * Like a business card for an Agent: what it can do, how to reach it. Served
 * at {@code /.well-known/agent.json} per the spec, and used for skill-based
 * routing ({@code AgentSupervisor.dispatchBySkill}).
 * <p>
 * The canonical 7-arg shape carries the spec's {@code url} (where the
 * endpoint actually lives; in-process agents have none) and
 * {@link A2ACapabilities}. The legacy 5-arg shape (Stage 10/11 callers)
 * delegates with url = endpoint and capabilities = none, so every existing
 * construction site compiles and behaves unchanged.
 * <p>
 * Trust note (Stage 11 D7): a remote card is a self-report. It is a ROUTING
 * input, never a TRUST input.
 *
 * @param name         Agent's display name
 * @param description  What this Agent does
 * @param skills       List of capabilities (e.g. ["code-review", "deployment"])
 * @param endpoint     How to reach this Agent (URL or identifier; for
 *                     in-process agents an "in-process:name" handle)
 * @param version      Protocol version
 * @param url          Spec url: the agent's real http(s) base (nullable)
 * @param capabilities Spec capability flags (streaming / push / history)
 */
public record AgentCard(
        String name,
        String description,
        List<String> skills,
        String endpoint,
        String version,
        String url,
        A2ACapabilities capabilities
) {
    public AgentCard {
        Objects.requireNonNull(name, "name must not be null");
        if (skills == null) {
            skills = List.of();
        }
        if (capabilities == null) {
            capabilities = A2ACapabilities.none();
        }
    }

    /**
     * Legacy 5-arg shape (Stage 10/11 callers): url mirrors endpoint,
     * capabilities default to none.
     */
    public AgentCard(String name, String description, List<String> skills,
                     String endpoint, String version) {
        this(name, description, skills, endpoint, version, endpoint, A2ACapabilities.none());
    }
}

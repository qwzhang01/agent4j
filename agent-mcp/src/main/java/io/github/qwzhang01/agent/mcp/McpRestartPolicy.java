package io.github.qwzhang01.agent.mcp;

/**
 * Restart budget for {@link ManagedMcpClient} (Stage 10 process management).
 * <p>
 * Prevents restart storms when a server is fundamentally broken (bad command,
 * corrupted package, crashes on startup):
 * <ul>
 *   <li>{@code maxRestarts} -- max restarts allowed within the window</li>
 *   <li>{@code cooldownMs} -- min time between two restarts (burst protection)</li>
 *   <li>{@code windowMs} -- after this much quiet time, the budget resets</li>
 * </ul>
 *
 * @param maxRestarts max restarts within the window (>= 1)
 * @param cooldownMs  min interval between restarts (>= 0)
 * @param windowMs    budget reset window (> 0)
 */
public record McpRestartPolicy(int maxRestarts, long cooldownMs, long windowMs) {

    public McpRestartPolicy {
        if (maxRestarts < 1) {
            throw new IllegalArgumentException("maxRestarts must be >= 1");
        }
        if (cooldownMs < 0) {
            throw new IllegalArgumentException("cooldownMs must be >= 0");
        }
        if (windowMs <= 0) {
            throw new IllegalArgumentException("windowMs must be > 0");
        }
    }

    /**
     * Sensible defaults: 3 restarts per minute, at least 5s apart.
     */
    public static McpRestartPolicy defaults() {
        return new McpRestartPolicy(3, 5_000, 60_000);
    }
}

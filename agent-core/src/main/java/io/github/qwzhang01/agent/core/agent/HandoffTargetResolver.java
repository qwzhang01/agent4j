package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.tool.ToolExecutor;

/**
 * Resolves a persisted agent name back to a live config, and the executor
 * that should run that config's tools after a handoff (Decision 24 P3).
 * <p>
 * The loop never silently falls back to the entry persona: an unknown
 * {@code lastActiveAgentName} is a wiring bug and must fail closed.
 * <p>
 * Default: {@link GraphHandoffTargetResolver} walks the handoff graph
 * from the entry config. Hosts that rebuild configs after a restart
 * (or keep a name → instance registry) supply their own resolver.
 */
public interface HandoffTargetResolver {

    /**
     * Map a persisted name to a live config reachable from {@code entry}.
     * {@code null} / blank names, and the entry's own name, return {@code entry}.
     *
     * @throws IllegalStateException if the name is not reachable
     */
    AgentConfig resolve(AgentConfig entry, String lastActiveName);

    /**
     * Executor for a swapped-in target. Prefer the target's own executor;
     * otherwise a plain executor over its registry. The loop never re-weaves
     * the host decorations it was constructed with.
     */
    ToolExecutor executorFor(AgentConfig target);

    static HandoffTargetResolver graph() {
        return GraphHandoffTargetResolver.INSTANCE;
    }
}

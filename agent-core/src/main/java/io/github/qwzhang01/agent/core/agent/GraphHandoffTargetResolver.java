package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;

/**
 * Default {@link HandoffTargetResolver}: walk the in-process handoff graph.
 * Names must be unique in the reachable graph; the first match wins.
 */
public final class GraphHandoffTargetResolver implements HandoffTargetResolver {

    static final GraphHandoffTargetResolver INSTANCE = new GraphHandoffTargetResolver();

    @Override
    public AgentConfig resolve(AgentConfig entry, String lastActiveName) {
        if (entry == null) {
            throw new IllegalArgumentException("entry config must not be null");
        }
        if (lastActiveName == null || lastActiveName.isBlank()
                || lastActiveName.equals(entry.getName())) {
            return entry;
        }
        AgentConfig found = find(entry, lastActiveName);
        if (found == null) {
            throw new IllegalStateException("lastActiveAgentName '" + lastActiveName
                    + "' is not reachable from entry '" + entry.getName() + "'");
        }
        return found;
    }

    @Override
    public ToolExecutor executorFor(AgentConfig target) {
        if (target.getToolExecutor() != null) {
            return target.getToolExecutor();
        }
        ToolRegistry registry = target.getToolRegistry();
        return registry != null
                ? new DefaultToolExecutor(registry)
                : new DefaultToolExecutor(new InMemoryToolRegistry());
    }

    private static AgentConfig find(AgentConfig start, String name) {
        Deque<AgentConfig> queue = new ArrayDeque<>();
        IdentityHashMap<AgentConfig, Boolean> seen = new IdentityHashMap<>();
        queue.add(start);
        seen.put(start, Boolean.TRUE);
        while (!queue.isEmpty()) {
            AgentConfig node = queue.removeFirst();
            if (name.equals(node.getName())) {
                return node;
            }
            for (HandoffSpec spec : node.getHandoffs()) {
                AgentConfig next = spec.target();
                if (seen.put(next, Boolean.TRUE) == null) {
                    queue.add(next);
                }
            }
        }
        return null;
    }
}

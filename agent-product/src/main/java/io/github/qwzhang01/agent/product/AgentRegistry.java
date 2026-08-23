package io.github.qwzhang01.agent.product;

import io.github.qwzhang01.agent.core.agent.Agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Live agent instances started from definitions (Stage 13 M13.1).
 * <p>
 * "Adding an agent = dropping a YAML file" - the registry is what
 * {@code ProductBootstrapper.startAll} fills and what callers (HTTP handlers,
 * webhooks in M13.5) look agents up from.
 * <p>
 * Duplicate registration fails fast: two agents under one name would make
 * webhook routing and audits ambiguous.
 */
public final class AgentRegistry {

    private final Map<String, Agent> agents = new LinkedHashMap<>();

    /**
     * Register a started agent under its definition name.
     */
    public AgentRegistry register(String name, Agent agent) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("agent name must not be blank");
        }
        if (agents.containsKey(name)) {
            throw new IllegalArgumentException("Agent '" + name + "' is already registered");
        }
        agents.put(name, agent);
        return this;
    }

    public Optional<Agent> get(String name) {
        return Optional.ofNullable(agents.get(name));
    }

    /**
     * All agents in registration order.
     */
    public List<Agent> list() {
        return List.copyOf(agents.values());
    }

    public int size() {
        return agents.size();
    }
}

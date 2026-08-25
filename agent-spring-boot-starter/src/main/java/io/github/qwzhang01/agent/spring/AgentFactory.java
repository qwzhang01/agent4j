package io.github.qwzhang01.agent.spring;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.SimpleAgent;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

import java.util.Objects;

/**
 * Creates {@link Agent} instances from the shared {@link ModelClient} bean.
 * <p>
 * Intentionally not a singleton {@code Agent} bean: applications such as Moonlit
 * have per-character system prompts and should call {@link #create(String, String)}
 * (or the tools overload) for each character.
 * <p>
 * Streaming: call {@code Agent.stream(userInput, listener)} on the returned
 * agent. This factory does not wrap streaming.
 */
public class AgentFactory {

    static final int DEFAULT_MAX_STEPS = 10;

    private final ModelClient modelClient;

    public AgentFactory(ModelClient modelClient) {
        this.modelClient = Objects.requireNonNull(modelClient, "modelClient");
    }

    /**
     * Create an agent with an empty tool registry and default max steps ({@value DEFAULT_MAX_STEPS}).
     *
     * @param name         agent name (used in config / observability)
     * @param systemPrompt per-character system prompt
     */
    public Agent create(String name, String systemPrompt) {
        return create(name, systemPrompt, new InMemoryToolRegistry(), DEFAULT_MAX_STEPS);
    }

    /**
     * Create an agent with an explicit tool registry and step bound.
     *
     * @param name         agent name
     * @param systemPrompt per-character system prompt
     * @param tools        tools available to this agent; {@code null} becomes an empty in-memory registry
     * @param maxSteps     safety bound against unbounded tool loops
     */
    public Agent create(String name, String systemPrompt, ToolRegistry tools, int maxSteps) {
        ToolRegistry registry = tools != null ? tools : new InMemoryToolRegistry();
        AgentConfig config = new AgentConfig(name, systemPrompt, modelClient, registry, maxSteps);
        return new SimpleAgent(config);
    }
}

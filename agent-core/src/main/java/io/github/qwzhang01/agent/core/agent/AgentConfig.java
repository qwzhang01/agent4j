package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

/**
 * Configuration for creating an Agent.
 * <p>
 * This is a value object that holds the "static blueprint" of an Agent:
 * - System prompt (personality / instructions)
 * - Model client (which LLM to use)
 * - Tool registry (what tools are available)
 * - Max steps (safety bound)
 * <p>
 * The "dynamic execution" is handled by AgentLoop.
 */
public class AgentConfig {

    private final String name;
    private final String systemPrompt;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;
    private final ContextBuilder contextBuilder;

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient, ToolRegistry toolRegistry) {
        this(name, systemPrompt, modelClient, toolRegistry, 10, null);
    }

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, null);
    }

    /**
     * Full constructor with context builder (Stage 8).
     * Pass {@code null} for contextBuilder to use the default passthrough behavior.
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder) {
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
        this.contextBuilder = contextBuilder;
    }

    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ModelClient getModelClient() {
        return modelClient;
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    public int getMaxSteps() {
        return maxSteps;
    }

    /**
     * Context builder for memory/context management (Stage 8).
     * Null means passthrough (Stage 1-7 behavior).
     */
    public ContextBuilder getContextBuilder() {
        return contextBuilder;
    }
}

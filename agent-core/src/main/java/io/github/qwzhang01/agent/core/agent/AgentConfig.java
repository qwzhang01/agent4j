package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

import java.util.List;

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
 * <p>
 * Handoffs (Stage 19): an agent may declare which agents it can transfer
 * the conversation to. The loop exposes each declaration as a
 * {@code transfer_to_<name>} tool; calling it swaps the active config while
 * the shared AgentState survives untouched.
 */
public class AgentConfig {

    private final String name;
    private final String systemPrompt;
    private final ModelClient modelClient;
    private final ToolRegistry toolRegistry;
    private final int maxSteps;
    private final ContextBuilder contextBuilder;
    private final List<HandoffSpec> handoffs;

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient, ToolRegistry toolRegistry) {
        this(name, systemPrompt, modelClient, toolRegistry, 10, null, List.of());
    }

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, null, List.of());
    }

    /**
     * Full constructor with context builder (Stage 8).
     * Pass {@code null} for contextBuilder to use the default passthrough behavior.
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, contextBuilder, List.of());
    }

    /**
     * Full constructor with handoffs (Stage 19).
     * <p>
     * Handoffs hold direct references to target configs, so circular graphs
     * (A can transfer to B and B back to A) assemble naturally — no
     * name-based lookup or post-assembly resolution pass.
     *
     * @param handoffs declared transfer targets; empty means no handoffs
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder,
                       List<HandoffSpec> handoffs) {
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
        this.contextBuilder = contextBuilder;
        this.handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
        requireNoSelfHandoff();
        requireNoToolNameCollision();
    }

    /** A handoff target must not be the declaring config itself. */
    private void requireNoSelfHandoff() {
        for (HandoffSpec spec : handoffs) {
            if (spec.target() == this) {
                throw new IllegalArgumentException("Agent '" + name
                        + "' cannot hand off to itself: tool '" + spec.toolName() + "'");
            }
        }
    }

    /**
     * A handoff tool name must not shadow a plain tool: the loop intercepts
     * handoff calls before the executor, so a collision would silently make
     * the plain tool unreachable. Fail at assembly, not mid-run.
     */
    private void requireNoToolNameCollision() {
        if (toolRegistry == null) {
            return;
        }
        for (HandoffSpec spec : handoffs) {
            if (toolRegistry.getTool(spec.toolName()).isPresent()) {
                throw new IllegalArgumentException("Agent '" + name
                        + "': handoff tool '" + spec.toolName()
                        + "' collides with a registered plain tool of the same name");
            }
        }
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

    /**
     * Declared handoff targets (Stage 19). Empty by default.
     */
    public List<HandoffSpec> getHandoffs() {
        return handoffs;
    }
}

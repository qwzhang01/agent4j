package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
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
 * Handoffs : an agent may declare which agents it can transfer
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
    private final GuardrailChain guardrails;
    private final ToolExecutor toolExecutor;

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient, ToolRegistry toolRegistry) {
        this(name, systemPrompt, modelClient, toolRegistry, 10, null, List.of());
    }

    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, null, List.of());
    }

    /**
     * Full constructor with context builder .
     * Pass {@code null} for contextBuilder to use the default passthrough behavior.
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, contextBuilder, List.of());
    }

    /**
     * Full constructor with handoffs .
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
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, contextBuilder, handoffs, null);
    }

    /**
     * Full constructor with KP5 input/output guardrails.
     * {@code null} guardrails means {@link GuardrailChain#none}.
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder,
                       List<HandoffSpec> handoffs, GuardrailChain guardrails) {
        this(name, systemPrompt, modelClient, toolRegistry, maxSteps, contextBuilder,
                handoffs, guardrails, null);
    }

    /**
     * Full constructor with a per-config {@link ToolExecutor} (Decision 24 P3).
     * {@code null} means the loop falls back to a plain executor over
     * {@code toolRegistry}. Attach a governed executor here so a handoff
     * target does not silently drop the host's audit / permission chain.
     */
    public AgentConfig(String name, String systemPrompt, ModelClient modelClient,
                       ToolRegistry toolRegistry, int maxSteps, ContextBuilder contextBuilder,
                       List<HandoffSpec> handoffs, GuardrailChain guardrails,
                       ToolExecutor toolExecutor) {
        this.name = name;
        this.systemPrompt = systemPrompt;
        this.modelClient = modelClient;
        this.toolRegistry = toolRegistry;
        this.maxSteps = maxSteps;
        this.contextBuilder = contextBuilder;
        this.handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
        this.guardrails = guardrails == null ? GuardrailChain.none() : guardrails;
        this.toolExecutor = toolExecutor;
        requireNoSelfHandoff();
        requireNoToolNameCollision();
    }

    /**
     * Copy with a different executor. Assemble the executor onto the target
     * <em>before</em> another config holds a handoff reference to it —
     * this method returns a new instance.
     */
    public AgentConfig withToolExecutor(ToolExecutor toolExecutor) {
        return new AgentConfig(name, systemPrompt, modelClient, toolRegistry, maxSteps,
                contextBuilder, handoffs, guardrails, toolExecutor);
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
     * Context builder for memory/context management .
     * Null means passthrough (-7 behavior).
     */
    public ContextBuilder getContextBuilder() {
        return contextBuilder;
    }

    /**
     * Declared handoff targets . Empty by default.
     */
    public List<HandoffSpec> getHandoffs() {
        return handoffs;
    }

    /**
     * Input/output guardrail chain (KP5). Never null; empty means the doors are open.
     */
    public GuardrailChain getGuardrails() {
        return guardrails;
    }

    /**
     * Optional executor for this config's plain tools (P3).
     * Null: the loop uses a plain {@code DefaultToolExecutor} over the registry.
     */
    public ToolExecutor getToolExecutor() {
        return toolExecutor;
    }
}

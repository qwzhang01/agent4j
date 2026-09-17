package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Default Agent implementation.
 * <p>
 * Combines AgentConfig (static blueprint) with AgentLoop (dynamic execution).
 * Accepts plain text or a multimodal {@link ChatMessage} (vision via {@code parts}).
 * Memory ({@link AgentConfig#getContextBuilder()}), tool governance
 * ({@link ReActAgentLoop} + {@code GovernedToolExecutor}) and checkpoints
 * plug in from the outside — this class stays a thin entry point.
 */
public class SimpleAgent implements Agent {

    static final String MAX_STEPS_PLACEHOLDER = "[Agent reached max steps without a final answer]";

    private final AgentConfig config;
    private final AgentLoop loop;

    public SimpleAgent(AgentConfig config) {
        this.config = config;
        this.loop = new ReActAgentLoop(executorOf(config));
    }

    public SimpleAgent(AgentConfig config, AgentLoop loop) {
        this.config = config;
        this.loop = loop;
    }

    @Override
    public String run(String userInput) {
        return run(ChatMessage.user(userInput), new AgentState());
    }

    @Override
    public String run(String userInput, AgentState state) {
        return run(ChatMessage.user(userInput), state);
    }

    @Override
    public String run(ChatMessage userMessage) {
        return run(userMessage, new AgentState());
    }

    @Override
    public String run(ChatMessage userMessage, AgentState state) {
        return run(userMessage, state, RunContext.create());
    }

    /** Stage 1.2: run with a RunContext (cancellation/deadline/identity ride along). */
    @Override
    public String run(ChatMessage userMessage, AgentState state, RunContext ctx) {
        prepare(userMessage, state);
        loop.execute(config, state, ctx != null ? ctx : RunContext.create());
        return extractFinalAnswer(state);
    }

    @Override
    public void stream(ChatMessage userMessage, AgentState state, Consumer<AgentEvent> listener) {
        stream(userMessage, state, listener, RunContext.create());
    }

    /** Stage 1.2: stream with a RunContext. */
    @Override
    public void stream(ChatMessage userMessage, AgentState state,
                       Consumer<AgentEvent> listener, RunContext ctx) {
        Objects.requireNonNull(listener, "listener");
        prepare(userMessage, state);
        loop.stream(config, state, listener, ctx != null ? ctx : RunContext.create());
    }

    /**
     * Resume a paused {@link AgentState.Status#WAITING_APPROVAL} run.
     * Does not append a user message — the pending tool calls stay paired
     * with the last assistant turn.
     */
    @Override
    public String resume(AgentState state, RunContext ctx) {
        Objects.requireNonNull(state, "state");
        if (state.getStatus() == AgentState.Status.WAITING_APPROVAL
                && (ctx == null || ctx.runId() == null || ctx.runId().isBlank())) {
            throw new IllegalArgumentException(
                    "resume of WAITING_APPROVAL requires a RunContext with runId");
        }
        state.setMaxSteps(config.getMaxSteps());
        loop.execute(config, state, ctx != null ? ctx : RunContext.create());
        return extractFinalAnswer(state);
    }

    private static ToolExecutor executorOf(AgentConfig config) {
        if (config.getToolExecutor() != null) {
            return config.getToolExecutor();
        }
        ToolRegistry registry = config.getToolRegistry();
        return new DefaultToolExecutor(registry != null ? registry : new InMemoryToolRegistry());
    }

    private void prepare(ChatMessage userMessage, AgentState state) {
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        if (userMessage.role() != ChatRole.USER) {
            throw new IllegalArgumentException("userMessage must have role USER");
        }
        state.addMessage(userMessage);

        // Config is the SSOT for the step cap. currentStep is NOT reset:
        // the budget is conversation-cumulative across run(input, state)
        // continuations. A restored state's maxSteps is overwritten to
        // match this agent; raise or tighten the cap on AgentConfig.
        state.setMaxSteps(config.getMaxSteps());
    }

    private static String extractFinalAnswer(AgentState state) {
        // Waiting is not a final answer even if the assistant turn carried
        // text alongside tool_calls (real providers often do).
        if (state.getStatus() == AgentState.Status.WAITING_APPROVAL) {
            return "[Agent waiting for approval]";
        }

        var messages = state.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() == ChatRole.ASSISTANT && msg.content() != null) {
                return msg.content();
            }
        }

        return switch (state.getStatus()) {
            case MAX_STEPS_EXCEEDED -> MAX_STEPS_PLACEHOLDER;
            case ERROR -> "[Agent error: " + state.getLastError() + "]";
            case CANCELLED -> "[Agent run cancelled]";
            default -> "[Agent did not produce a final answer]";
        };
    }

    @Override
    public AgentConfig getConfig() {
        return config;
    }
}

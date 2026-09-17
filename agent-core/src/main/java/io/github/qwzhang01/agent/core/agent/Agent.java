package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ContentPart;
import io.github.qwzhang01.agent.core.run.RunContext;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Interface for an Agent.
 * <p>
 * Design principle: Agent is the entry point for callers.
 * Internally it delegates to AgentLoop for the actual execution.
 * <p>
 * The Agent interface is intentionally minimal:
 * - run(userInput): one-shot execution
 * - run(userInput, state): continuation with existing state (multi-turn)
 * - stream(userInput, listener): same run, but tokens/tool events as they arrive
 * <p>
 * Default {@code stream} implementations fall back to {@link #run} then one
 * {@link AgentEvent.ContentDelta} plus {@link AgentEvent.Done}, so stubs and
 * decorators that only implement {@code run} keep compiling.
 * <p>
 * Stage 2: implement ReAct loop via AgentLoop
 * Stage 5+: add workflow graph execution
 */
public interface Agent {

    /**
     * Run the agent with a user input.
     * Creates a fresh AgentState and runs to completion (or error/max-steps).
     *
     * @param userInput user's question or instruction
     * @return agent's final response text
     */
    String run(String userInput);

    /**
     * Run the agent with a user input, continuing from an existing state.
     * Used for multi-turn conversations.
     * <p>
     * Continuation contract (P3): {@link AgentState} stores
     * {@code lastActiveAgentName} (a name, never a config reference).
     * Re-entering through THIS agent resolves that name via
     * {@link HandoffTargetResolver} and resumes as the last handoff target.
     * Old checkpoints without the field still start as the entry persona.
     * <p>
     * Step budget: {@code currentStep} is conversation-cumulative and is
     * not reset between turns. {@link AgentConfig#getMaxSteps()} is the
     * SSOT for the cap ({@code SimpleAgent} overwrites {@code state.maxSteps}
     * on every prepare). Tighten or raise the cap by changing the config,
     * not by mutating the state.
     *
     * @param userInput user's question or instruction
     * @param state     existing conversation state (will be mutated)
     * @return agent's final response text
     */
    String run(String userInput, AgentState state);

    /**
     * Run with a pre-built USER message (text or multimodal via {@link ChatMessage#parts()}).
     */
    default String run(ChatMessage userMessage) {
        return run(userMessage, new AgentState());
    }

    /**
     * Continue a conversation with a pre-built USER message (text or multimodal).
     * <p>
     * Default: degrade to {@link #run(String, AgentState)} using
     * {@code content()}, or concatenated text parts if content is blank.
     * Image-only parts are dropped — override this method to keep them.
     * Agents that only implement the String overloads stay callable
     * through {@code run(ChatMessage)} / default {@code stream} without
     * throwing at runtime.
     */
    default String run(ChatMessage userMessage, AgentState state) {
        Objects.requireNonNull(userMessage, "userMessage");
        return run(textOf(userMessage), state);
    }

    /** Text fallback for agents that do not consume multimodal parts. */
    private static String textOf(ChatMessage message) {
        if (message.content() != null && !message.content().isBlank()) {
            return message.content();
        }
        if (message.parts() == null || message.parts().isEmpty()) {
            return message.content() != null ? message.content() : "";
        }
        String joined = message.parts().stream()
                .filter(ContentPart.TextPart.class::isInstance)
                .map(part -> ((ContentPart.TextPart) part).text())
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
        return joined.isBlank() && message.content() != null ? message.content() : joined;
    }

    /**
     * Stream a run from a fresh state. Default: {@link #run} then one delta + Done.
     */
    default void stream(String userInput, Consumer<AgentEvent> listener) {
        stream(ChatMessage.user(userInput), new AgentState(), listener);
    }

    /**
     * Stream a run continuing from {@code state}. Default: {@link #run} then one delta + Done.
     */
    default void stream(String userInput, AgentState state, Consumer<AgentEvent> listener) {
        stream(ChatMessage.user(userInput), state, listener);
    }

    /**
     * Stream a run with a pre-built USER message from a fresh state.
     */
    default void stream(ChatMessage userMessage, Consumer<AgentEvent> listener) {
        stream(userMessage, new AgentState(), listener);
    }

    /**
     * Stream a run with a pre-built USER message, continuing from {@code state}.
     * <p>
     * Fallback for agents that do not override streaming: call {@code run},
     * emit one {@link AgentEvent.ContentDelta} if the answer is non-blank,
     * then {@link AgentEvent.Error} or {@link AgentEvent.Done}.
     */
    default void stream(ChatMessage userMessage, AgentState state, Consumer<AgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        String answer = run(userMessage, state);
        if (answer != null && !answer.isBlank()) {
            listener.accept(new AgentEvent.ContentDelta(answer));
        }
        if (state.getStatus() == AgentState.Status.ERROR) {
            listener.accept(new AgentEvent.Error(state.getLastError(), null));
            return;
        }
        listener.accept(new AgentEvent.Done(answer, state));
    }

    /**
     * Get the agent's configuration.
     */
    AgentConfig getConfig();

    // ============ Stage 1.2: RunContext-aware overloads ============

    /**
     * Run with an explicit {@link RunContext} (Stage 1.2 of the harness
     * roadmap): identity, tenant, deadline, cancellation and budget ride
     * one immutable context instead of scattered parameters. Legacy
     * {@code run} methods keep working unchanged (context-free path).
     *
     * @param userMessage user's question or instruction
     * @param state       existing conversation state (will be mutated)
     * @param ctx         the run context (null falls back to the legacy path)
     * @return agent's final response text
     */
    default String run(ChatMessage userMessage, AgentState state, RunContext ctx) {
        throw new UnsupportedOperationException("This agent does not support RunContext");
    }

    /**
     * String-input convenience for the ctx-aware run.
     */
    default String run(String userInput, AgentState state, RunContext ctx) {
        return run(ChatMessage.user(userInput), state, ctx);
    }

    /**
     * Stream with an explicit {@link RunContext}. See {@link #run(ChatMessage, AgentState, RunContext)}.
     */
    default void stream(ChatMessage userMessage, AgentState state,
                        Consumer<AgentEvent> listener, RunContext ctx) {
        throw new UnsupportedOperationException("This agent does not support RunContext");
    }

    /**
     * Resume a {@link AgentState.Status#WAITING_APPROVAL} run without
     * appending a new user message. Default throws; {@link SimpleAgent}
     * re-enters the loop so pending tool calls can be retried after a
     * human decision lands.
     */
    default String resume(AgentState state, RunContext ctx) {
        throw new UnsupportedOperationException("This agent does not support resume");
    }
}

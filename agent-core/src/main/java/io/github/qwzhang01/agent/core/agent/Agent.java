package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.Objects;
import java.util.function.Consumer;

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
     */
    default String run(ChatMessage userMessage, AgentState state) {
        throw new UnsupportedOperationException("This agent does not support ChatMessage input");
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
}

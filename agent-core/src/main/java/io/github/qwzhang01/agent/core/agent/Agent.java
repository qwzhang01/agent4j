package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;

/**
 * Interface for an Agent.
 * <p>
 * Design principle: Agent is the entry point for callers.
 * Internally it delegates to AgentLoop for the actual execution.
 * <p>
 * The Agent interface is intentionally minimal:
 * - run(userInput): one-shot execution
 * - run(userInput, state): continuation with existing state (multi-turn)
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
     * Get the agent's configuration.
     */
    AgentConfig getConfig();
}

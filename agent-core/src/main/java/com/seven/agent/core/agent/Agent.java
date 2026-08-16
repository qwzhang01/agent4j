package com.seven.agent.core.agent;

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
     * Get the agent's configuration.
     */
    AgentConfig getConfig();
}

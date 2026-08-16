package com.seven.agent.core.agent;

/**
 * The core Agent execution loop (ReAct pattern).
 * <p>
 * Flow:
 * <pre>{@code
 * while (state.hasStepsRemaining() && !state.isTerminal()) {
 *     1. Build ModelRequest from state (messages + tool schemas)
 *     2. Call ModelClient.chat(request)
 *     3. If response has tool calls:
 *        - Execute each tool via ToolExecutor
 *        - Add tool results to state
 *        - Continue loop
 *     4. If response is final (no tool calls):
 *        - Set status to DONE
 *        - Break
 *     5. Increment step
 * }
 * }</pre>
 * <p>
 * This is the heart of the framework. Everything else (tools, memory,
 * checkpoint, policy) plugs into this loop.
 * <p>
 * Design principle: the loop is a function, not a thread. It takes state
 * and returns updated state. This makes it testable and composable.
 */
public interface AgentLoop {

    /**
     * Execute the agent loop until completion, error, or max steps.
     *
     * @param config agent configuration
     * @param state  mutable agent state (will be updated in place)
     * @return the final state
     */
    AgentState execute(AgentConfig config, AgentState state);
}

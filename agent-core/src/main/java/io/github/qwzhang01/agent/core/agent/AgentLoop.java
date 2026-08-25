package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.List;
import java.util.function.Consumer;

/**
 * The core Agent execution loop (ReAct pattern).
 * <p>
 * Flow:
 * <pre>{@code
 * while (state.hasStepsRemaining() && !state.isTerminal()) {
 *     1. Build ModelRequest from state (messages + tool schemas)
 *     2. Call ModelClient.chat(request)  — or ModelClient.stream for stream()
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

    /**
     * Stream the same loop as {@link #execute}, pushing {@link AgentEvent}s to {@code sink}.
     * <p>
     * Default: run {@code execute} then emit {@link AgentEvent.Error} or {@link AgentEvent.Done}.
     * {@link ReActAgentLoop} overrides this to consume {@code ModelClient.stream}.
     */
    default void stream(AgentConfig config, AgentState state, Consumer<AgentEvent> sink) {
        execute(config, state);
        String answer = lastAssistantContent(state);
        if (state.getStatus() == AgentState.Status.ERROR) {
            sink.accept(new AgentEvent.Error(state.getLastError(), null));
            return;
        }
        sink.accept(new AgentEvent.Done(answer, state));
    }

    private static String lastAssistantContent(AgentState state) {
        List<ChatMessage> messages = state.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg.role() == ChatRole.ASSISTANT && msg.content() != null) {
                return msg.content();
            }
        }
        return "";
    }
}

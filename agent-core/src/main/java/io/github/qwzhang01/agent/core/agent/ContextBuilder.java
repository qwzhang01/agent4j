package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Builds the message list sent to the model from the current agent state.
 * <p>
 * This is the extension point for memory and context management (Stage 8):
 * - retrieval of long-term memories to inject into context
 * - token budget enforcement
 * - compaction / compression of old messages (pi-style)
 * <p>
 * When an {@link AgentConfig} has no context builder ({@code null}),
 * {@link ReActAgentLoop} falls back to passing {@code state.getMessages()}
 * directly, preserving Stage 1-7 behavior (backward compatible).
 */
public interface ContextBuilder {

    /**
     * Build the messages for the next model request.
     * <p>
     * Implementations MAY mutate {@code state.getMessages()} in place
     * (e.g. a compressing builder rewrites history to stay within budget).
     * This is intentional: the checkpointed state should match what was
     * actually sent to the model (Stage 8 D4).
     *
     * @param config agent configuration
     * @param state  current agent state (mutable)
     * @return messages to send to the model
     */
    List<ChatMessage> build(AgentConfig config, AgentState state);
}

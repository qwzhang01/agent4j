package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.List;

/**
 * Builds history and transient context, excluding the agent persona.
 * {@link ReActAgentLoop} prepends the active configuration's system prompt
 * AFTER this builder runs; implementations must not inject the persona themselves.
 * Transient SYSTEM context (for example host-authored retry guidance) may be returned,
 * but must never be written into AgentState. Role alone does not identify a persona.
 * <p>
 * This is the extension point for memory and context management (Stage 8):
 * - retrieval of long-term memories to inject into context
 * - token budget enforcement
 * - compaction / compression of old messages (pi-style)
 * <p>
 * When an {@link AgentConfig} has no context builder ({@code null}),
 * {@link ReActAgentLoop} falls back to passing {@code state.getMessages()}
 * through, then prepends the current system prompt at the model boundary.
 */
public interface ContextBuilder {

    /**
     * Build the messages for the next model request.
     * <p>
     * Implementations MAY mutate {@code state.getMessages()} in place
     * (e.g. a compressing builder rewrites history to stay within budget).
     * Checkpoints retain conversation state, not the final model request.
     * Model-boundary recording is responsible for the full request, including
     * instructions and transient retrieval. Budgeting builders must reserve space
     * for config.getSystemPrompt(), tool schemas and model output separately.
     *
     * @param config agent configuration
     * @param state  current agent state (mutable)
     * @return history/transient context without persona; may be immutable or state-backed
     */
    List<ChatMessage> build(AgentConfig config, AgentState state);
}

package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.run.RunContext;

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
 * Prefix-stability clause (E3, decision 26): implementations SHOULD keep the
 * leading portion of the returned message list stable across calls within one
 * conversation. Prompt caches (KV caches) bill by longest-common-prefix: a
 * rewritten leading history destroys the accumulated hit and re-pays the
 * cache-write premium on providers that charge one (Anthropic-style explicit
 * caching: read 0.1x, write 1.25x). When compaction MUST rewrite, prefer a
 * FROZEN summary text that later turns append onto, over a summary that
 * changes every call - E3 measured a 24% cache-value loss from flapping
 * summaries alone (same volumes, only stability differed). Caveat: under
 * providers with free implicit caching (OpenAI-style), stability buys less
 * and aggressive compaction may win on volume - the discipline is priced by
 * the provider, not universal. Violating this clause is not a compile error;
 * it is a billing event. Visibility (cachedTokens in TokenUsage) is the
 * enforcement mechanism of this contract, not a type-system check.
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

    /**
     * Build with the run context (Stage 1.2). Default: legacy path.
     * Memory-side implementations override this to read tenant/user
     * identity from the context for scope filtering (Stage 5 governance
     * hook; today the scopes list is fixed at construction time).
     *
     * @param config agent configuration
     * @param state  current agent state (mutable)
     * @param ctx    the run context (may be null on the legacy path)
     * @return history/transient context without persona
     */
    default List<ChatMessage> build(AgentConfig config, AgentState state, RunContext ctx) {
        return build(config, state);
    }
}

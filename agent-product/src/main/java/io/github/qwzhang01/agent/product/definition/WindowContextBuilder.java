package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in short-term memory strategy: keep the system prompt plus the most recent
 * N messages (Stage 13 M13.1, {@code spec.memory.shortTerm: {strategy: window}}).
 * <p>
 * Read-time trimming: the returned list is what the model sees; the agent state
 * keeps the FULL history (trace/audit stay complete). This is deliberately
 * different from {@code CompressingContextBuilder} (Stage 8), which rewrites
 * state in place - windowing is lossy visibility, compaction is lossy state.
 * <p>
 * Naming honesty: the YAML field is {@code maxMessages} (messages, not turns) -
 * one turn is typically two messages and conflating them has burned every
 * chat product at least once.
 */
public final class WindowContextBuilder implements ContextBuilder {

    private final int maxMessages;

    /**
     * @param maxMessages messages kept verbatim after the system prompt (&gt; 0)
     */
    public WindowContextBuilder(int maxMessages) {
        if (maxMessages <= 0) {
            throw new IllegalArgumentException("maxMessages must be positive, got " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        List<ChatMessage> messages = state.getMessages();
        if (messages.size() <= maxMessages) {
            return new ArrayList<>(messages);
        }

        List<ChatMessage> window = new ArrayList<>(maxMessages + 1);

        // Keep the leading system prompt (persona) if present.
        int from = messages.size() - maxMessages;
        if (!messages.isEmpty() && messages.get(0).role() == ChatRole.SYSTEM) {
            window.add(messages.get(0));
            from = Math.max(from, 1);
        }

        window.addAll(messages.subList(from, messages.size()));
        return window;
    }
}

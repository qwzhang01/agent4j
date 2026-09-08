package io.github.qwzhang01.agent.product.definition;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Built-in short-term memory strategy: keep the most recent N history
 * messages (Stage 13 M13.1, {@code spec.memory.shortTerm: {strategy: window}}).
 * <p>
 * Read-time trimming: the loop prepends the persona after this builder; the agent state
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
     * @param maxMessages history messages kept verbatim (&gt; 0), excluding instructions
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

        return new ArrayList<>(messages.subList(messages.size() - maxMessages, messages.size()));
    }
}

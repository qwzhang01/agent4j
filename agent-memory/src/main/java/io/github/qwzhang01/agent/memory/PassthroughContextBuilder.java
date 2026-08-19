package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.agent.AgentConfig;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.agent.ContextBuilder;
import io.github.qwzhang01.agent.core.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Default passthrough context builder - returns a copy of the current messages.
 * <p>
 * This is the no-op baseline. When no memory/context management is needed,
 * this (or simply leaving {@code contextBuilder == null} on AgentConfig)
 * preserves Stage 1-7 behavior exactly.
 */
public class PassthroughContextBuilder implements ContextBuilder {

    @Override
    public List<ChatMessage> build(AgentConfig config, AgentState state) {
        return new ArrayList<>(state.getMessages());
    }
}

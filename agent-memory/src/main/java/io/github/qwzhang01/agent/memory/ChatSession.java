package io.github.qwzhang01.agent.memory;

import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;

import java.util.ArrayList;
import java.util.List;

/**
 * Session-level conversation holder (Stage 8 - session memory layer).
 * <p>
 * Holds the multi-turn message history that spans across Agent runs within
 * one conversation. Each turn:
 * <ol>
 *   <li>{@link #toAgentState} builds a fresh AgentState from session history + system prompt</li>
 *   <li>The Agent runs (producing new messages in AgentState)</li>
 *   <li>{@link #syncFrom} pulls the updated non-system messages back into session history</li>
 * </ol>
 * <p>
 * This is the "Session Memory" layer in the three-tier model (Stage 8 §2.1):
 * Working = AgentState (run-scoped), Session = this, Long-term = MemoryStore.
 */
public class ChatSession {

    private final String sessionId;
    private final List<ChatMessage> history = new ArrayList<>();

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<ChatMessage> getHistory() {
        return history;
    }

    public void addUser(String content) {
        history.add(ChatMessage.user(content));
    }

    public void addAssistant(String content) {
        history.add(ChatMessage.assistant(content));
    }

    /**
     * Build a fresh AgentState from session history + system prompt.
     */
    public AgentState toAgentState(String systemPrompt) {
        AgentState state = new AgentState();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            state.addMessage(ChatMessage.system(systemPrompt));
        }
        for (ChatMessage msg : history) {
            state.addMessage(msg);
        }
        return state;
    }

    /**
     * Sync non-system messages from AgentState back into session history.
     * Called after an Agent run to capture new messages.
     */
    public void syncFrom(AgentState state) {
        history.clear();
        for (ChatMessage msg : state.getMessages()) {
            if (msg.role() != ChatRole.SYSTEM) {
                history.add(msg);
            }
        }
    }
}

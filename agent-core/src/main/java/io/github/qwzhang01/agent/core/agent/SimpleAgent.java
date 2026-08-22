package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

/**
 * Default Agent implementation.
 * <p>
 * Combines AgentConfig (static blueprint) with AgentLoop (dynamic execution).
 * Accepts plain text or a multimodal {@link ChatMessage} (vision via {@code parts}).
 * Memory ({@link AgentConfig#getContextBuilder()}), tool governance
 * ({@link ReActAgentLoop} + {@code GovernedToolExecutor}) and checkpoints
 * plug in from the outside — this class stays a thin entry point.
 */
public class SimpleAgent implements Agent {

    private final AgentConfig config;
    private final AgentLoop loop;

    public SimpleAgent(AgentConfig config) {
        this.config = config;
        ToolRegistry registry = config.getToolRegistry();
        this.loop = new ReActAgentLoop(registry != null ? registry : new InMemoryToolRegistry());
    }

    public SimpleAgent(AgentConfig config, AgentLoop loop) {
        this.config = config;
        this.loop = loop;
    }

    @Override
    public String run(String userInput) {
        return run(ChatMessage.user(userInput), new AgentState());
    }

    @Override
    public String run(String userInput, AgentState state) {
        return run(ChatMessage.user(userInput), state);
    }

    @Override
    public String run(ChatMessage userMessage) {
        return run(userMessage, new AgentState());
    }

    @Override
    public String run(ChatMessage userMessage, AgentState state) {
        if (userMessage == null) {
            throw new IllegalArgumentException("userMessage must not be null");
        }
        if (userMessage.role() != ChatRole.USER) {
            throw new IllegalArgumentException("userMessage must have role USER");
        }
        if (state.getMessages().isEmpty() && config.getSystemPrompt() != null) {
            state.addMessage(ChatMessage.system(config.getSystemPrompt()));
        }
        state.addMessage(userMessage);

        state.setMaxSteps(config.getMaxSteps());
        loop.execute(config, state);

        return extractFinalAnswer(state);
    }

    private static String extractFinalAnswer(AgentState state) {
        var messages = state.getMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            var msg = messages.get(i);
            if (msg.role() == ChatRole.ASSISTANT && msg.content() != null) {
                return msg.content();
            }
        }

        return switch (state.getStatus()) {
            case MAX_STEPS_EXCEEDED -> "[Agent reached max steps without a final answer]";
            case ERROR -> "[Agent error: " + state.getLastError() + "]";
            default -> "[Agent did not produce a final answer]";
        };
    }

    @Override
    public AgentConfig getConfig() {
        return config;
    }
}

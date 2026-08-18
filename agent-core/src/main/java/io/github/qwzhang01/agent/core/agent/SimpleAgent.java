package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.tool.InMemoryToolRegistry;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;

/**
 * Default Agent implementation.
 * <p>
 * Combines AgentConfig (static blueprint) with AgentLoop (dynamic execution).
 * This is the simplest possible Agent: create state -> run loop -> return result.
 * <p>
 * Later stages will add:
 * - Memory integration (stage 8)
 * - Checkpoint before/after each step (stage 6)
 * - Policy checks (stage 9)
 * - Trace recording (stage 18)
 * - Trajectory export (stage 14)
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
        return run(userInput, new AgentState());
    }

    @Override
    public String run(String userInput, AgentState state) {
        if (state.getMessages().isEmpty() && config.getSystemPrompt() != null) {
            state.addMessage(ChatMessage.system(config.getSystemPrompt()));
        }
        state.addMessage(ChatMessage.user(userInput));

        state.setMaxSteps(config.getMaxSteps());
        loop.execute(config, state);

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

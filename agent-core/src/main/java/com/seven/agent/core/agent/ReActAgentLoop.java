package com.seven.agent.core.agent;

import com.seven.agent.core.client.ModelClient;
import com.seven.agent.core.model.*;
import com.seven.agent.core.tool.DefaultToolExecutor;
import com.seven.agent.core.tool.ToolExecutor;
import com.seven.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Default ReAct (Reason-Act) AgentLoop implementation.
 * <p>
 * This is the heart of the framework. The loop:
 * 1. Builds a ModelRequest from current state
 * 2. Calls the model
 * 3. If the model requests tool calls -> execute tools -> add results -> loop
 * 4. If the model gives a final answer -> set DONE -> return
 * 5. Enforce max steps as a safety bound
 * <p>
 * Everything else in the framework (memory, checkpoint, policy, sandbox)
 * will plug into this loop via hooks/decorators in later stages.
 */
public class ReActAgentLoop implements AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(ReActAgentLoop.class);

    private final ToolExecutor toolExecutor;

    public ReActAgentLoop(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    /**
     * Convenience constructor: creates a DefaultToolExecutor from registry.
     */
    public ReActAgentLoop(ToolRegistry registry) {
        this(new DefaultToolExecutor(registry));
    }

    @Override
    public AgentState execute(AgentConfig config, AgentState state) {
        ModelClient modelClient = config.getModelClient();
        state.setStatus(AgentState.Status.RUNNING);

        while (state.hasStepsRemaining() && !state.isTerminal()) {
            state.incrementStep();
            log.debug("[{}] Step {}", config.getName(), state.getCurrentStep());

            // --------------------------------------------
            // 1. Build model request from current state
            // --------------------------------------------
            ModelRequest request = buildRequest(config, state);

            // --------------------------------------------
            // 2. Call the model
            // --------------------------------------------
            ModelResponse response;
            try {
                response = modelClient.chat(request);
            } catch (Exception e) {
                log.error("[{}] Model call failed at step {}: {}",
                        config.getName(), state.getCurrentStep(), e.getMessage());
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("Model call failed: " + e.getMessage());
                return state;
            }

            // --------------------------------------------
            // 3. Handle response: tool calls or final answer
            // --------------------------------------------
            if (response.hasToolCalls()) {
                // Add assistant message with tool calls to history
                state.addMessage(ChatMessage.assistantWithTools(
                        response.content(), response.toolCalls()));

                // Execute each tool call
                state.setStatus(AgentState.Status.EXECUTING_TOOL);
                for (ToolCall toolCall : response.toolCalls()) {
                    log.info("[{}] Executing tool: {}", config.getName(), toolCall.name());
                    String result = toolExecutor.execute(toolCall);
                    // Add tool result to conversation
                    state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(), result));
                }

                state.setStatus(AgentState.Status.RUNNING);
            } else {
                // Model gave a final answer
                state.addMessage(ChatMessage.assistant(response.content()));
                state.setStatus(AgentState.Status.DONE);
                log.info("[{}] Completed in {} steps", config.getName(), state.getCurrentStep());
                return state;
            }
        }

        // --------------------------------------------
        // 4. Max steps exceeded
        // --------------------------------------------
        if (!state.hasStepsRemaining()) {
            log.warn("[{}] Max steps ({}) exceeded", config.getName(), state.getMaxSteps());
            state.setStatus(AgentState.Status.MAX_STEPS_EXCEEDED);
        }

        return state;
    }

    // ============ Private Helpers ============

    private ModelRequest buildRequest(AgentConfig config, AgentState state) {
        var builder = ModelRequest.builder()
                .messages(new ArrayList<>(state.getMessages()));

        // Attach tool schemas if registry has tools
        ToolRegistry registry = config.getToolRegistry();
        if (registry != null && !registry.listTools().isEmpty()) {
            builder.tools(registry.getToolSchemas());
        }

        return builder.build();
    }
}

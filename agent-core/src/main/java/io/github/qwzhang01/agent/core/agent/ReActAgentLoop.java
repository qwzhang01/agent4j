package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

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

    @Override
    public void stream(AgentConfig config, AgentState state, Consumer<AgentEvent> sink) {
        ModelClient modelClient = config.getModelClient();
        state.setStatus(AgentState.Status.RUNNING);

        while (state.hasStepsRemaining() && !state.isTerminal()) {
            state.incrementStep();
            log.debug("[{}] Step {}", config.getName(), state.getCurrentStep());

            ModelRequest request = buildRequest(config, state);

            ModelResponse response;
            try (Stream<StreamEvent> events = modelClient.stream(request)) {
                response = consumeStream(events, state, sink);
            } catch (Exception e) {
                log.error("[{}] Model stream failed at step {}: {}",
                        config.getName(), state.getCurrentStep(), e.getMessage());
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("Model call failed: " + e.getMessage());
                sink.accept(new AgentEvent.Error(state.getLastError(), e));
                return;
            }

            if (state.getStatus() == AgentState.Status.ERROR) {
                return;
            }
            if (response == null) {
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("Stream ended without a Done event");
                sink.accept(new AgentEvent.Error(state.getLastError(), null));
                return;
            }

            if (response.hasToolCalls()) {
                state.addMessage(ChatMessage.assistantWithTools(
                        response.content(), response.toolCalls()));

                state.setStatus(AgentState.Status.EXECUTING_TOOL);
                for (ToolCall toolCall : response.toolCalls()) {
                    log.info("[{}] Executing tool: {}", config.getName(), toolCall.name());
                    sink.accept(new AgentEvent.ToolStarted(toolCall));
                    String result = toolExecutor.execute(toolCall);
                    state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(), result));
                    sink.accept(new AgentEvent.ToolFinished(toolCall.id(), toolCall.name(), result));
                }

                state.setStatus(AgentState.Status.RUNNING);
            } else {
                state.addMessage(ChatMessage.assistant(response.content()));
                state.setStatus(AgentState.Status.DONE);
                log.info("[{}] Completed in {} steps", config.getName(), state.getCurrentStep());
                String answer = response.content() != null ? response.content() : "";
                sink.accept(new AgentEvent.Done(answer, state));
                return;
            }
        }

        if (!state.hasStepsRemaining()) {
            log.warn("[{}] Max steps ({}) exceeded", config.getName(), state.getMaxSteps());
            state.setStatus(AgentState.Status.MAX_STEPS_EXCEEDED);
            sink.accept(new AgentEvent.Done(SimpleAgent.MAX_STEPS_PLACEHOLDER, state));
        }
    }

    /**
     * Drain a model stream. Emits {@link AgentEvent.ContentDelta} for non-blank
     * chunks. Incremental {@link StreamEvent.ToolCallEvent}s are ignored until
     * {@link StreamEvent.Done} carries the complete {@link ModelResponse}.
     *
     * @return the final response, or {@code null} if the stream ended without Done
     *         (or after an Error event, in which case state is already ERROR)
     */
    private static ModelResponse consumeStream(Stream<StreamEvent> events, AgentState state,
                                               Consumer<AgentEvent> sink) {
        ModelResponse response = null;
        Iterator<StreamEvent> iterator = events.iterator();
        while (iterator.hasNext()) {
            StreamEvent event = iterator.next();
            if (event instanceof StreamEvent.ContentDelta delta) {
                if (delta.delta() != null && !delta.delta().isBlank()) {
                    sink.accept(new AgentEvent.ContentDelta(delta.delta()));
                }
            } else if (event instanceof StreamEvent.ToolCallEvent) {
                // Incremental; wait for Done.finalResponse() before executing.
            } else if (event instanceof StreamEvent.Done done) {
                response = done.finalResponse();
                break;
            } else if (event instanceof StreamEvent.Error err) {
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError(err.message());
                sink.accept(new AgentEvent.Error(err.message(), err.cause()));
                return null;
            }
        }
        return response;
    }

    // ============ Private Helpers ============

    private ModelRequest buildRequest(AgentConfig config, AgentState state) {
        // Stage 8: use ContextBuilder if configured, otherwise passthrough (backward compatible)
        List<ChatMessage> messages = config.getContextBuilder() != null
                ? config.getContextBuilder().build(config, state)
                : new ArrayList<>(state.getMessages());

        var builder = ModelRequest.builder()
                .messages(messages);

        // Attach tool schemas if registry has tools
        ToolRegistry registry = config.getToolRegistry();
        if (registry != null && !registry.listTools().isEmpty()) {
            builder.tools(registry.getToolSchemas());
        }

        return builder.build();
    }
}

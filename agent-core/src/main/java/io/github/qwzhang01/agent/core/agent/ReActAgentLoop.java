package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
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
import java.util.Objects;
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
    private final HandoffTargetResolver resolver;

    public ReActAgentLoop(ToolExecutor toolExecutor) {
        this(toolExecutor, HandoffTargetResolver.graph());
    }

    public ReActAgentLoop(ToolExecutor toolExecutor, HandoffTargetResolver resolver) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.resolver = resolver == null ? HandoffTargetResolver.graph() : resolver;
    }

    /**
     * Convenience constructor: creates a DefaultToolExecutor from registry.
     */
    public ReActAgentLoop(ToolRegistry registry) {
        this(new DefaultToolExecutor(registry));
    }

    @Override
    public AgentState execute(AgentConfig config, AgentState state) {
        // Non-streaming = the same loop with a no-op event sink.
        runLoop(config, state, e -> { },
                (client, request, s, sink) -> client.chat(request));
        return state;
    }

    @Override
    public void stream(AgentConfig config, AgentState state, Consumer<AgentEvent> sink) {
        runLoop(config, state, sink, ReActAgentLoop::invokeStream);
    }

    /**
     * The single ReAct loop. Streaming is not a second algorithm: it is the
     * same state transition projected through a different event sink.
     * {@link #execute} uses a no-op sink and returns the final state;
     * {@link #stream} forwards {@link AgentEvent}s to the caller's sink.
     * <p>
     * Stage 19: the loop does not own a single config. It starts with the
     * entry config, but a declared handoff swaps the active config mid-run
     * (persona, model client, tools, context builder) while the shared
     * {@link AgentState} — history plus the global step budget — survives.
     *
     * @param invoker produces the model response; may emit intermediate
     *                {@link AgentEvent}s (e.g. ContentDelta) while doing so
     */
    private void runLoop(AgentConfig config, AgentState state, Consumer<AgentEvent> sink,
                         ModelInvoker invoker) {
        AgentConfig currentConfig = resolver.resolve(config, state.getLastActiveAgentName());
        AgentConfig handoffFrom = null;
        HandoffInputFilter activeInputFilter = HandoffInputFilter.IDENTITY;
        state.setStatus(AgentState.Status.RUNNING);

        while (state.hasStepsRemaining() && !state.isTerminal()) {
            state.incrementStep();
            log.debug("[{}] Step {}", currentConfig.getName(), state.getCurrentStep());

            // --------------------------------------------
            // 1. Build model request from current state
            // --------------------------------------------
            ModelRequest request = buildRequest(currentConfig, state, handoffFrom, activeInputFilter);
            request = applyInputGuardrails(currentConfig, state, request, sink);
            if (state.getStatus() == AgentState.Status.ERROR) {
                return;
            }

            // --------------------------------------------
            // 2. Call the model (via the CURRENT config's client)
            // --------------------------------------------
            ModelResponse response;
            try {
                response = invoker.invoke(currentConfig.getModelClient(), request, state, sink);
            } catch (Exception e) {
                log.error("[{}] Model call failed at step {}: {}",
                        currentConfig.getName(), state.getCurrentStep(), e.getMessage());
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

            // --------------------------------------------
            // 3. Handle response: tool calls or final answer
            // --------------------------------------------
            if (response.hasToolCalls()) {
                // Add assistant message with tool calls to history
                state.addMessage(ChatMessage.assistantWithTools(
                        response.content(), response.toolCalls()));

                // Execute each tool call. Plain tools run first in order;
                // a handoff executes last, after this response's remaining
                // plain work is done and recorded.
                state.setStatus(AgentState.Status.EXECUTING_TOOL);
                ToolCall pendingHandoffCall = null;
                HandoffSpec pendingHandoffSpec = null;
                for (ToolCall toolCall : response.toolCalls()) {
                    HandoffSpec spec = findDeclaredHandoff(currentConfig, toolCall);
                    if (spec != null) {
                        if (pendingHandoffCall != null) {
                            // Multiple handoff calls in one response: the first wins,
                            // later ones are unreachable because the active config
                            // changes. Record as a tool error to keep every
                            // toolCall paired with a toolResult.
                            state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(),
                                    "[ERROR] Handoff '" + toolCall.name() + "' skipped: another "
                                            + "handoff in the same response already transferred "
                                            + "the conversation."));
                            continue;
                        }
                        pendingHandoffCall = toolCall;
                        pendingHandoffSpec = spec;
                        continue;
                    }
                    log.info("[{}] Executing tool: {}", currentConfig.getName(), toolCall.name());
                    sink.accept(new AgentEvent.ToolStarted(toolCall));
                    String result = executePlainTool(config, currentConfig, toolCall);
                    // Add tool result to conversation
                    state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(), result));
                    sink.accept(new AgentEvent.ToolFinished(toolCall.id(), toolCall.name(), result));
                }

                if (pendingHandoffCall != null) {
                    // Synthetic tool result must reference the model-generated
                    // tool_use id, so the assistant toolCall stays paired with a
                    // tool result (provider-required invariant).
                    state.addMessage(ChatMessage.tool(pendingHandoffCall.id(),
                            pendingHandoffCall.name(),
                            "Conversation transferred to agent '" + pendingHandoffSpec.targetName() + "'."));
                    AgentConfig fromConfig = currentConfig;
                    currentConfig = pendingHandoffSpec.target();
                    state.setLastActiveAgentName(currentConfig.getName());
                    handoffFrom = fromConfig;
                    activeInputFilter = pendingHandoffSpec.inputFilter();
                    log.info("[{}] Handoff via '{}': now running as [{}]",
                            fromConfig.getName(), pendingHandoffSpec.toolName(), currentConfig.getName());
                    sink.accept(new AgentEvent.Handoff(fromConfig.getName(), currentConfig.getName(),
                            pendingHandoffSpec.toolName()));
                }

                state.setStatus(AgentState.Status.RUNNING);
            } else {
                // Model gave a final answer — output door runs before state or Done.
                String answer = response.content() != null ? response.content() : "";
                String guarded = applyOutputGuardrails(currentConfig, state, answer, sink);
                if (state.getStatus() == AgentState.Status.ERROR) {
                    return;
                }
                state.addMessage(ChatMessage.assistant(guarded));
                state.setStatus(AgentState.Status.DONE);
                log.info("[{}] Completed in {} steps", currentConfig.getName(), state.getCurrentStep());
                sink.accept(new AgentEvent.Done(guarded, state));
                return;
            }
        }

        // --------------------------------------------
        // 4. Max steps exceeded
        // --------------------------------------------
        if (!state.hasStepsRemaining()) {
            log.warn("[{}] Max steps ({}) exceeded", currentConfig.getName(), state.getMaxSteps());
            state.setStatus(AgentState.Status.MAX_STEPS_EXCEEDED);
            sink.accept(new AgentEvent.Done(SimpleAgent.MAX_STEPS_PLACEHOLDER, state));
        }
    }

    /**
     * Returns the declared handoff spec matching this tool call, or null for
     * plain tools. A handoff is only recognized when the CURRENT config
     * declared it — a model cannot reach an undeclared agent.
     */
    private HandoffSpec findDeclaredHandoff(AgentConfig currentConfig, ToolCall toolCall) {
        for (HandoffSpec spec : currentConfig.getHandoffs()) {
            if (spec.toolName().equals(toolCall.name())) {
                return spec;
            }
        }
        return null;
    }

    /**
     * Runs a plain tool under the CURRENT config's executor.
     * <p>
     * Iron rule: the ENTRY config always routes through the executor this
     * loop was constructed with (which hosts may have wrapped with
     * governance/audit decorators). A swapped-in target uses
     * {@link HandoffTargetResolver#executorFor} — typically the target's
     * own {@link AgentConfig#getToolExecutor()}, else a plain
     * {@link DefaultToolExecutor}. The loop never re-weaves host decorations.
     */
    private String executePlainTool(AgentConfig entryConfig, AgentConfig currentConfig, ToolCall toolCall) {
        if (currentConfig == entryConfig) {
            return toolExecutor.execute(toolCall);
        }
        return executorFor(currentConfig).execute(toolCall);
    }

    /**
     * Cache of resolvers' executors for swapped-in target configs.
     * Keyed by config identity — AgentConfig has no equals (identity by
     * design), so the map degrades gracefully if a host rebuilds configs.
     */
    private final java.util.Map<AgentConfig, ToolExecutor> targetExecutors =
            new java.util.IdentityHashMap<>();

    private ToolExecutor executorFor(AgentConfig currentConfig) {
        return targetExecutors.computeIfAbsent(currentConfig, resolver::executorFor);
    }

    /**
     * Produces a model response for the unified loop. Implementations may emit
     * intermediate {@link AgentEvent}s while producing the response.
     */
    @FunctionalInterface
    private interface ModelInvoker {
        ModelResponse invoke(ModelClient modelClient, ModelRequest request,
                             AgentState state, Consumer<AgentEvent> sink) throws Exception;
    }

    private static ModelResponse invokeStream(ModelClient modelClient, ModelRequest request,
                                              AgentState state, Consumer<AgentEvent> sink) throws Exception {
        try (Stream<StreamEvent> events = modelClient.stream(request)) {
            return consumeStream(events, state, sink);
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

    private ModelRequest buildRequest(AgentConfig config, AgentState state,
                                      AgentConfig handoffFrom, HandoffInputFilter inputFilter) {
        // Reject legacy personas before a builder can trim/compact them away.
        requireHistoryOnly(state.getMessages());
        List<ChatMessage> context = config.getContextBuilder() != null
                ? config.getContextBuilder().build(config, state)
                : state.getMessages();
        requireHistoryOnly(state.getMessages());
        if (handoffFrom != null && inputFilter != null) {
            context = inputFilter.filter(context, handoffFrom, config);
        }

        // Own the request list: a builder may return an immutable list or live state.
        List<ChatMessage> messages = new ArrayList<>(context.size() + 1);
        String systemPrompt = config.getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(ChatMessage.system(systemPrompt));
        }
        messages.addAll(context);

        var builder = ModelRequest.builder()
                .messages(messages);

        // Attach tool schemas if registry has tools
        ToolRegistry registry = config.getToolRegistry();
        List<String> schemas = new ArrayList<>();
        if (registry != null && !registry.listTools().isEmpty()) {
            schemas.addAll(registry.getToolSchemas());
        }
        // Handoff tools (Stage 19): expose declared transfer targets to the
        // model as no-arg tools, alongside plain tools.
        if (!config.getHandoffs().isEmpty()) {
            for (HandoffSpec spec : config.getHandoffs()) {
                schemas.add(spec.toolSchema());
            }
        }
        if (!schemas.isEmpty()) {
            builder.tools(schemas);
        }

        return builder.build();
    }

    /**
     * INPUT door: ContextBuilder already ran. Block refuses the turn.
     * Rewrite changes the request only — AgentState keeps the original user text.
     */
    private ModelRequest applyInputGuardrails(AgentConfig config, AgentState state,
                                              ModelRequest request, Consumer<AgentEvent> sink) {
        GuardrailChain chain = config.getGuardrails();
        if (chain.isEmpty()) {
            return request;
        }
        String text = lastUserText(request.messages());
        GuardrailVerdict verdict = chain.evaluate(
                new GuardrailContext(GuardrailPhase.INPUT, text, config, state));
        if (verdict instanceof GuardrailVerdict.Block block) {
            state.setStatus(AgentState.Status.ERROR);
            state.setLastError("input guardrail blocked: " + block.reason());
            sink.accept(new AgentEvent.Error(state.getLastError(), null));
            return request;
        }
        if (verdict instanceof GuardrailVerdict.Rewrite rewrite) {
            return withLastUserText(request, rewrite.replacement());
        }
        return request;
    }

    /**
     * OUTPUT door: before the answer is written to state or Done.
     * Returns the text to persist, or leaves state in ERROR on Block.
     */
    private String applyOutputGuardrails(AgentConfig config, AgentState state,
                                         String answer, Consumer<AgentEvent> sink) {
        GuardrailChain chain = config.getGuardrails();
        if (chain.isEmpty()) {
            return answer;
        }
        GuardrailVerdict verdict = chain.evaluate(
                new GuardrailContext(GuardrailPhase.OUTPUT, answer, config, state));
        if (verdict instanceof GuardrailVerdict.Block block) {
            state.setStatus(AgentState.Status.ERROR);
            state.setLastError("output guardrail blocked: " + block.reason());
            sink.accept(new AgentEvent.Error(state.getLastError(), null));
            return answer;
        }
        if (verdict instanceof GuardrailVerdict.Rewrite rewrite) {
            return rewrite.replacement();
        }
        return answer;
    }

    private static String lastUserText(List<ChatMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (message.role() == ChatRole.USER) {
                return message.content() == null ? "" : message.content();
            }
        }
        return "";
    }

    private static ModelRequest withLastUserText(ModelRequest request, String replacement) {
        List<ChatMessage> original = request.messages();
        List<ChatMessage> copy = new ArrayList<>(original.size());
        int lastUser = -1;
        for (int i = original.size() - 1; i >= 0; i--) {
            if (original.get(i).role() == ChatRole.USER) {
                lastUser = i;
                break;
            }
        }
        for (int i = 0; i < original.size(); i++) {
            ChatMessage message = original.get(i);
            if (i == lastUser) {
                copy.add(ChatMessage.user(replacement));
            } else {
                copy.add(message);
            }
        }
        return ModelRequest.builder()
                .model(request.model())
                .messages(copy)
                .tools(request.tools())
                .temperature(request.temperature())
                .maxTokens(request.maxTokens())
                .stream(request.stream())
                .responseFormat(request.responseFormat())
                .reasoning(request.reasoning())
                .build();
    }

    private static void requireHistoryOnly(List<ChatMessage> messages) {
        if (messages.stream().anyMatch(message -> message.role() == ChatRole.SYSTEM)) {
            throw new IllegalArgumentException("SYSTEM instructions must come from AgentConfig.systemPrompt, "
                    + "not AgentState. For old checkpoints, explicitly call "
                    + "AgentState.migrateLegacySystemPrompt with the old persona; "
                    + "migrate other SYSTEM events separately.");
        }
    }
}

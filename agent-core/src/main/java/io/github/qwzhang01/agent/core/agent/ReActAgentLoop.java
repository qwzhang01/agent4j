package io.github.qwzhang01.agent.core.agent;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.RunCancelledException;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.run.RunDeadlineException;
import io.github.qwzhang01.agent.core.run.RunEvent;
import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
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

    /**
     * Stage 9 Tool Parallelism: when non-null and a response carries more
     * than one plain tool call, the loop fans them out through this
     * executor (bounded width, declaration-order join). Null = sequential
     * legacy behavior, bit-for-bit.
     */
    private ParallelToolExecutor parallelToolExecutor;

    public ReActAgentLoop(ToolExecutor toolExecutor) {
        this(toolExecutor, HandoffTargetResolver.graph());
    }

    public ReActAgentLoop(ToolExecutor toolExecutor, HandoffTargetResolver resolver) {
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.resolver = resolver == null ? HandoffTargetResolver.graph() : resolver;
    }

    /**
     * Stage 9: opt in to parallel plain-tool execution. Injected after
     * construction (the loop's existing constructors stay unchanged for
     * binary compatibility).
     */
    public ReActAgentLoop withParallelTools(ParallelToolExecutor parallelToolExecutor) {
        this.parallelToolExecutor = parallelToolExecutor;
        return this;
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

    // ============ Stage 1.2: ctx-aware execution ============

    /**
     * Execute with a {@link RunContext}: cancellation and deadline are
     * checked at every step boundary; the context is propagated to the
     * model boundary (via the ctx-aware ModelClient overload) and to the
     * tool boundary (via the ctx-aware ToolExecutor overload). Without a
     * context the loop behaves exactly as before.
     */
    @Override
    public AgentState execute(AgentConfig config, AgentState state, RunContext ctx) {
        runLoop(config, state, e -> { }, ctx,
                (client, request, s, sink, c) -> client.chat(request, c));
        return state;
    }

    /**
     * Stream with a {@link RunContext}. Same propagation as the ctx-aware
     * {@link #execute(AgentConfig, AgentState, RunContext)}.
     */
    @Override
    public void stream(AgentConfig config, AgentState state, Consumer<AgentEvent> sink,
                       RunContext ctx) {
        runLoop(config, state, sink, ctx, ReActAgentLoop::invokeStreamCtx);
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
        runLoop(config, state, sink, null, adapt(invoker));
    }

    /** Legacy invoker adapted to the ctx-aware signature (ctx = null). */
    private static CtxModelInvoker adapt(ModelInvoker invoker) {
        return (client, request, s, sink, ctx) -> invoker.invoke(client, request, s, sink);
    }

    /**
     * The ctx-aware loop (Stage 1.2). Null ctx = legacy behaviour, bit-for-bit.
     * With a context: every step boundary checks cancellation (structured
     * RunCancelledException) and deadline (RunDeadlineException); the model
     * call and every tool call receive the context via their ctx-aware
     * overloads.
     * <p>
     * Stage 7.1: when {@code ctx.eventSink()} is present, the loop emits the
     * eight lifecycle facts (RunStarted / StepStarted / StepCompleted /
     * RunCompleted / RunFailed / RunCanceled; pause/resume belong to the
     * workflow layer). Emission failures never break the run they observe -
     * telemetry is a side channel (same discipline as MetricsSink).
     */
    private void runLoop(AgentConfig config, AgentState state, Consumer<AgentEvent> sink,
                         RunContext ctx, CtxModelInvoker invoker) {
        AgentConfig currentConfig = resolver.resolve(config, state.getLastActiveAgentName());
        AgentConfig handoffFrom = null;
        HandoffInputFilter activeInputFilter = HandoffInputFilter.IDENTITY;
        state.setStatus(AgentState.Status.RUNNING);
        java.util.function.Consumer<RunEvent> events = ctx != null ? ctx.eventSink() : null;
        long runStartNanos = System.nanoTime();
        if (events != null) {
            emit(events, new RunEvent.RunStarted(ctx.runId(), ctx.traceId(), Instant.now()));
        }

        while (state.hasStepsRemaining() && !state.isTerminal()) {
            // Stage 1.4: cooperative cancellation + deadline check at the
            // step boundary. Structured signals, never free-text errors.
            if (ctx != null) {
                try {
                    ctx.checkAlive();
                } catch (RunCancelledException | RunDeadlineException signal) {
                    state.setStatus(AgentState.Status.CANCELLED);
                    state.setLastError(signal.getMessage());
                    sink.accept(new AgentEvent.Error(signal.getClass().getSimpleName()
                            + ": " + signal.getMessage(), signal));
                    if (events != null) {
                        emit(events, new RunEvent.RunCanceled(ctx.runId(), ctx.traceId(),
                                signal.getMessage(), Instant.now()));
                    }
                    return;
                }
            }
            state.incrementStep();
            log.debug("[{}] Step {}", currentConfig.getName(), state.getCurrentStep());
            String stepId = "step-" + state.getCurrentStep();
            int attempt = 1;
            if (events != null) {
                emit(events, new RunEvent.StepStarted(ctx.runId(), ctx.traceId(),
                        stepId, state.getCurrentStep(), attempt, Instant.now()));
            }
            long stepStartNanos = System.nanoTime();

            // --------------------------------------------
            // 1. Build model request from current state
            // --------------------------------------------
            ModelRequest request = buildRequest(currentConfig, state, handoffFrom, activeInputFilter, ctx);
            request = applyInputGuardrails(currentConfig, state, request, sink);
            if (state.getStatus() == AgentState.Status.ERROR) {
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "input guardrail blocked", FailureKind.INPUT_INVALID, Instant.now()));
                    emit(events, new RunEvent.RunFailed(ctx.runId(), ctx.traceId(),
                            FailureKind.INPUT_INVALID, state.getLastError(), Instant.now()));
                }
                return;
            }

            // --------------------------------------------
            // 2. Call the model (via the CURRENT config's client)
            // --------------------------------------------
            ModelResponse response;
            // Stage 9: model-boundary facts. One Started/Finished pair per
            // model call, emitted around the invoker regardless of outcome.
            sink.accept(new AgentEvent.ModelCallStarted(currentConfig.getName(),
                    request.model(), request.messages().size(), state.getCurrentStep()));
            long modelCallStart = System.nanoTime();
            try {
                response = invoker.invoke(currentConfig.getModelClient(), request, state, sink, ctx);
            } catch (Exception e) {
                sink.accept(new AgentEvent.ModelCallFinished(currentConfig.getName(),
                        request.model(), elapsedMs(modelCallStart), 0, true));
                log.error("[{}] Model call failed at step {}: {}",
                        currentConfig.getName(), state.getCurrentStep(), e.getMessage());
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("Model call failed: " + e.getMessage());
                sink.accept(new AgentEvent.Error(state.getLastError(), e));
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "model call failed", FailureKind.MODEL_FAILURE, Instant.now()));
                    emit(events, new RunEvent.RunFailed(ctx.runId(), ctx.traceId(),
                            FailureKind.MODEL_FAILURE, state.getLastError(), Instant.now()));
                }
                return;
            }

            if (state.getStatus() == AgentState.Status.ERROR) {
                sink.accept(new AgentEvent.ModelCallFinished(currentConfig.getName(),
                        request.model(), elapsedMs(modelCallStart), 0, true));
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "stream error", FailureKind.MODEL_FAILURE, Instant.now()));
                    emit(events, new RunEvent.RunFailed(ctx.runId(), ctx.traceId(),
                            FailureKind.MODEL_FAILURE, state.getLastError(), Instant.now()));
                }
                return;
            }
            if (response == null) {
                sink.accept(new AgentEvent.ModelCallFinished(currentConfig.getName(),
                        request.model(), elapsedMs(modelCallStart), 0, true));
                state.setStatus(AgentState.Status.ERROR);
                state.setLastError("Stream ended without a Done event");
                sink.accept(new AgentEvent.Error(state.getLastError(), null));
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "stream ended without Done", FailureKind.MODEL_FAILURE, Instant.now()));
                    emit(events, new RunEvent.RunFailed(ctx.runId(), ctx.traceId(),
                            FailureKind.MODEL_FAILURE, state.getLastError(), Instant.now()));
                }
                return;
            }
            sink.accept(new AgentEvent.ModelCallFinished(currentConfig.getName(),
                    request.model(), elapsedMs(modelCallStart),
                    response.hasToolCalls() ? response.toolCalls().size() : 0, false));

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
                List<ToolCall> plainCalls = new java.util.ArrayList<>();
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
                    plainCalls.add(toolCall);
                }

                if (parallelToolExecutor != null && plainCalls.size() > 1) {
                    // Stage 9 Tool Parallelism: fan out plain calls, join
                    // in declaration order. Budget/cancel/order/merge
                    // semantics live in ParallelToolExecutor; the loop
                    // keeps the event pairing (Started before dispatch,
                    // Finished after each result lands).
                    for (ToolCall toolCall : plainCalls) {
                        sink.accept(new AgentEvent.ToolStarted(toolCall));
                    }
                    final AgentConfig entryCfg = config;
                    final AgentConfig activeCfg = currentConfig;
                    final RunContext runCtx = ctx;
                    List<String> results = parallelToolExecutor.dispatchAll(
                            plainCalls.stream()
                                    .<ParallelToolExecutor.Dispatch>map(tc -> new ParallelToolExecutor.Dispatch(
                                            tc, tc2 -> executePlainTool(entryCfg, activeCfg, tc2, runCtx)))
                                    .toList());
                    for (int i = 0; i < plainCalls.size(); i++) {
                        ToolCall toolCall = plainCalls.get(i);
                        String result = results.get(i);
                        state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(), result));
                        sink.accept(new AgentEvent.ToolFinished(toolCall.id(), toolCall.name(), result));
                        AgentEvent.ToolValidationRejected rejected = governanceRejectionOf(toolCall, result);
                        if (rejected != null) {
                            sink.accept(rejected);
                        }
                    }
                } else {
                    for (ToolCall toolCall : plainCalls) {
                        log.info("[{}] Executing tool: {}", currentConfig.getName(), toolCall.name());
                        sink.accept(new AgentEvent.ToolStarted(toolCall));
                        String result = executePlainTool(config, currentConfig, toolCall, ctx);
                        // Add tool result to conversation
                        state.addMessage(ChatMessage.tool(toolCall.id(), toolCall.name(), result));
                        sink.accept(new AgentEvent.ToolFinished(toolCall.id(), toolCall.name(), result));
                        AgentEvent.ToolValidationRejected rejected = governanceRejectionOf(toolCall, result);
                        if (rejected != null) {
                            sink.accept(rejected);
                        }
                    }
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
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "tools executed", null, Instant.now()));
                }
            } else {
                // Model gave a final answer — output door runs before state or Done.
                String answer = response.content() != null ? response.content() : "";
                String guarded = applyOutputGuardrails(currentConfig, state, answer, sink);
                if (state.getStatus() == AgentState.Status.ERROR) {
                    if (events != null) {
                        emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                                state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                                "output guardrail blocked", FailureKind.INPUT_INVALID, Instant.now()));
                        emit(events, new RunEvent.RunFailed(ctx.runId(), ctx.traceId(),
                                FailureKind.INPUT_INVALID, state.getLastError(), Instant.now()));
                    }
                    return;
                }
                state.addMessage(ChatMessage.assistant(guarded));
                state.setStatus(AgentState.Status.DONE);
                log.info("[{}] Completed in {} steps", currentConfig.getName(), state.getCurrentStep());
                sink.accept(new AgentEvent.Done(guarded, state));
                if (events != null) {
                    emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(), stepId,
                            state.getCurrentStep(), attempt, elapsedMs(stepStartNanos),
                            "done", null, Instant.now()));
                    emit(events, new RunEvent.RunCompleted(ctx.runId(), ctx.traceId(),
                            elapsedMs(runStartNanos), state.getCurrentStep(), Instant.now()));
                }
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
            if (events != null) {
                emit(events, new RunEvent.StepCompleted(ctx.runId(), ctx.traceId(),
                        "step-" + state.getCurrentStep(), state.getCurrentStep(), 1,
                        elapsedMs(runStartNanos), "max steps exceeded",
                        FailureKind.RESOURCE_EXHAUSTED, Instant.now()));
                emit(events, new RunEvent.RunCompleted(ctx.runId(), ctx.traceId(),
                        elapsedMs(runStartNanos), state.getCurrentStep(), Instant.now()));
            }
        }
    }

    /** Side-channel emission: telemetry failures never break the observed run. */
    private static void emit(java.util.function.Consumer<RunEvent> events, RunEvent event) {
        try {
            events.accept(event);
        } catch (RuntimeException e) {
            log.warn("run event sink failed (events are a side channel, swallowing): {}", e.toString());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
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
     * <p>
     * Stage 1.2: the ctx-aware overload carries the run context through to
     * the tool boundary (governance decorators read identity/budget from it).
     */
    private String executePlainTool(AgentConfig entryConfig, AgentConfig currentConfig,
                                    ToolCall toolCall, RunContext ctx) {
        if (currentConfig == entryConfig) {
            return ctx != null
                    ? toolExecutor.execute(toolCall, ctx)
                    : toolExecutor.execute(toolCall);
        }
        ToolExecutor target = executorFor(currentConfig);
        return ctx != null
                ? target.execute(toolCall, ctx)
                : target.execute(toolCall);
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

    /**
     * Stage 1.2: ctx-aware model invoker. Null ctx = legacy invocation.
     */
    @FunctionalInterface
    private interface CtxModelInvoker {
        ModelResponse invoke(ModelClient modelClient, ModelRequest request,
                             AgentState state, Consumer<AgentEvent> sink, RunContext ctx)
                throws Exception;
    }

    private static ModelResponse invokeStream(ModelClient modelClient, ModelRequest request,
                                              AgentState state, Consumer<AgentEvent> sink) throws Exception {
        try (Stream<StreamEvent> events = modelClient.stream(request)) {
            return consumeStream(events, state, sink);
        }
    }

    /** Stage 1.2: stream via the ctx-aware ModelClient overload. */
    private static ModelResponse invokeStreamCtx(ModelClient modelClient, ModelRequest request,
                                                 AgentState state, Consumer<AgentEvent> sink,
                                                 RunContext ctx) throws Exception {
        try (Stream<StreamEvent> events = ctx != null
                ? modelClient.stream(request, ctx)
                : modelClient.stream(request)) {
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
                                      AgentConfig handoffFrom, HandoffInputFilter inputFilter,
                                      RunContext ctx) {
        // Reject legacy personas before a builder can trim/compact them away.
        requireHistoryOnly(state.getMessages());
        List<ChatMessage> context = config.getContextBuilder() != null
                ? (ctx != null
                        ? config.getContextBuilder().build(config, state, ctx)
                        : config.getContextBuilder().build(config, state))
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

    /**
     * Stage 9: a tool result that is a governance refusal (not a tool
     * output) gets an explicit {@code ToolValidationRejected} event — the
     * observability twin of the model-visible error string. Currently the
     * wired refusals are the validation boundaries of
     * {@code ContractAwareToolExecutor} ({@code [UNKNOWN_TOOL]} /
     * {@code [INVALID_TOOL_ARGUMENTS]}); permission/approval boundaries
     * are roadmap classes that will emit the same event with their stage
     * names when they land.
     */
    private static AgentEvent.ToolValidationRejected governanceRejectionOf(
            ToolCall toolCall, String result) {
        if (result == null) {
            return null;
        }
        if (result.startsWith("[UNKNOWN_TOOL]")
                || result.startsWith("[INVALID_TOOL_ARGUMENTS]")) {
            return new AgentEvent.ToolValidationRejected(
                    toolCall.id(), toolCall.name(), "validation", result);
        }
        return null;
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

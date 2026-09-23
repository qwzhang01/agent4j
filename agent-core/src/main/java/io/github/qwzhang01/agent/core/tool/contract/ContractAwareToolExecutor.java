package io.github.qwzhang01.agent.core.tool.contract;

import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.run.FailureKind;
import io.github.qwzhang01.agent.core.run.RunCancelledException;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.core.run.RunDeadlineException;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Validation-first tool executor (, harness roadmap).
 * <p>
 * One executor decorator, applied identically to built-ins, MCP tools and
 * plugins — the same validation chain for everything. Sits <b>outside</b>
 * governance (the order contract: validate → permission → rate-limit →
 * approval → execute → sanitize → audit): arguments that fail the contract
 * never reach permission checks, because a malformed call has no business
 * consuming governance decisions.
 * <p>
 * What it enforces per call:
 * <ol>
 *   <li>Unknown tool → refused with {@code [UNKNOWN_TOOL]} (the honest
 *       refusal the secure assembly wants, instead of a string the model
 *       might mistake for a result).</li>
 *   <li>Argument validation via {@link ToolArgumentValidator} against
 *       {@link Tool#definition} — invalid arguments produce
 *       {@code [INVALID_TOOL_ARGUMENTS]} classified as
 *       {@link FailureKind#INPUT_INVALID}, and the tool is <b>not</b>
 *       executed.</li>
 *   <li>Per-call timeout from the definition, enforced on a worker thread
 *       so a hung tool cannot pin the loop thread.</li>
 *   <li>Structured signals: {@link RunCancelledException} /
 *       {@link RunDeadlineException} pass through untouched (control flow,
 *       not tool failures).</li>
 *   <li>Everything the tool throws is wrapped in a
 *       {@link ToolResult} envelope carrying the failure kind,
 *       argument hash and model-visible text; the loop still receives a
 *       String (compatibility), but audits and tests can consume the typed
 *       envelope via {@link #lastResult(String)}.</li>
 * </ol>
 * <p>
 * Legacy compatibility: a delegate without ctx support is called through
 * the legacy overload; tools without a declared schema get structural
 * caps only (size/depth), never a free pass.
 * <p>
 * Thread note: the timeout watchdog uses a small cached daemon pool shared
 * across calls. Cancelling the run interrupts the worker, but a tool that
 * ignores interruption may keep running until its own timeout — that is
 * the documented limit of cooperative cancellation (hardens it).
 */
public class ContractAwareToolExecutor implements ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ContractAwareToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolExecutor delegate;
    private final ExecutorService watchdog;
    private final boolean ownsWatchdog;
    /** runId → last ToolResult envelope, for audits and tests (bounded by runs). */
    private final Map<String, ToolResult> lastResults = new ConcurrentHashMap<>();

    public ContractAwareToolExecutor(ToolRegistry registry, ToolExecutor delegate) {
        this(registry, delegate, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "tool-timeout-watchdog");
            t.setDaemon(true);
            return t;
        }), true);
    }

    ContractAwareToolExecutor(ToolRegistry registry, ToolExecutor delegate,
                              ExecutorService watchdog, boolean ownsWatchdog) {
        this.registry = registry;
        this.delegate = delegate;
        this.watchdog = watchdog;
        this.ownsWatchdog = ownsWatchdog;
    }

    @Override
    public String execute(ToolCall toolCall) {
        return execute(toolCall, null);
    }

    @Override
    public String execute(ToolCall toolCall, RunContext ctx) {
        var toolOpt = registry.getTool(toolCall.name());
        if (toolOpt.isEmpty()) {
            String refusal = "[UNKNOWN_TOOL] Tool '" + toolCall.name() + "' is not registered";
            log.warn(refusal);
            record(ctx, ToolResult.systemFailure(refusal,
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments()))));
            return refusal;
        }

        Tool tool = toolOpt.get();
        ToolDefinition definition = tool.definition();
        ValidationResult validation =
                ToolArgumentValidator.validate(toolCall.arguments(), definition);
        if (!validation.valid()) {
            String refusal = "[INVALID_TOOL_ARGUMENTS] "
                    + validation.firstError()
                    + (validation.unknownFields().isEmpty()
                        ? "" : " (unknown fields ignored: " + validation.unknownFields() + ")");
            log.warn("Tool '{}' rejected invalid arguments: {}", toolCall.name(), validation.errors());
            ToolResult envelope = new ToolResult(ToolResult.Outcome.BUSINESS_REJECTED,
                    refusal, FailureKind.INPUT_INVALID, validation.firstError(),
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments())),
                    0, null, false);
            record(ctx, envelope);
            return refusal;
        }

        // Side-effect tools are identity-bound: no RunContext means the
        // call has no tenant/run to attribute, so it cannot run. Read-shaped
        // tools (NONE / READ_ONLY) still execute on the legacy path.
        if (ctx == null && !definition.sideEffectLevel().isReadShaped()) {
            String refusal = "[DENIED] side-effect tool '" + toolCall.name()
                    + "' requires RunContext";
            log.warn(refusal);
            record(null, ToolResult.systemFailure(refusal,
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments()))));
            return refusal;
        }

        // Cancel/deadline pre-check before spending a watchdog thread.
        if (ctx != null) {
            ctx.checkAlive();
        }

        long timeoutMs = definition.timeout() == null
                ? ToolDefinition.DEFAULT_TIMEOUT.toMillis()
                : definition.timeout().toMillis();

        final String result;
        Future<String> future = null;
        try {
            if (ctx != null && deadlineBeforeToolTimeout(ctx, timeoutMs)) {
                // Run deadline fires sooner than the tool timeout: give the
                // tool only the remaining runway, not its full budget.
                long runway = java.time.Duration.between(
                        java.time.Instant.now(), ctx.deadline()).toMillis();
                future = watchdog.submit(() -> delegate.execute(toolCall, ctx));
                result = future.get(runway, TimeUnit.MILLISECONDS);
            } else if (ctx != null) {
                future = watchdog.submit(() -> delegate.execute(toolCall, ctx));
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                future = watchdog.submit(() -> delegate.execute(toolCall));
                result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            }
        } catch (RunCancelledException | RunDeadlineException signal) {
            if (future != null) {
                future.cancel(true);
            }
            throw signal; // control flow, not a tool failure
        } catch (java.util.concurrent.ExecutionException e) {
            if (future != null) {
                future.cancel(true);
            }
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RunDeadlineException) {
                throw (RunDeadlineException) cause;
            }
            if (cause instanceof RunCancelledException) {
                throw (RunCancelledException) cause;
            }
            String error = cause.getMessage() != null
                    ? cause.getMessage() : cause.getClass().getSimpleName();
            log.error("Tool '{}' failed: {}", toolCall.name(), error);
            ToolResult envelope = ToolResult.systemFailure(error,
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments())));
            record(ctx, envelope);
            if (cause instanceof RuntimeException runtimeCause) {
                throw runtimeCause;
            }
            throw new IllegalStateException("Tool '" + toolCall.name() + "' failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (future != null) {
                future.cancel(true);
            }
            ToolResult envelope = ToolResult.cancelled(
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments())));
            record(ctx, envelope);
            return envelope.modelVisibleText();
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            String detail = "timeout " + timeoutMs + "ms exceeded for tool '" + toolCall.name() + "'";
            log.warn("[Timeout] {}", detail);
            ToolResult envelope = ToolResult.timeout(detail,
                    ToolResult.hashArguments(String.valueOf(toolCall.arguments())));
            record(ctx, envelope);
            return envelope.modelVisibleText();
        }

        if (isInnerGovernanceRefusal(result)) {
            record(ctx, envelopeForGovernanceRefusal(result, toolCall));
            return result;
        }
        ToolResult envelope = ToolResult.success(result);
        record(ctx, envelope);
        return result;
    }

    private static boolean isInnerGovernanceRefusal(String result) {
        return result != null && (result.startsWith("[DENIED]")
                || result.startsWith("[WAITING_APPROVAL]")
                || result.startsWith("[RATE_LIMITED]"));
    }

    private static ToolResult envelopeForGovernanceRefusal(String result, ToolCall toolCall) {
        FailureKind kind = result.startsWith("[WAITING_APPROVAL]")
                ? FailureKind.APPROVAL_WAITING
                : result.startsWith("[RATE_LIMITED]")
                    ? FailureKind.RESOURCE_EXHAUSTED
                    : FailureKind.PERMISSION_DENIED;
        return new ToolResult(ToolResult.Outcome.BUSINESS_REJECTED, result, kind, result,
                ToolResult.hashArguments(String.valueOf(toolCall.arguments())),
                0, null, false);
    }

    private boolean deadlineBeforeToolTimeout(RunContext ctx, long timeoutMs) {
        return ctx.deadline() != null
                && java.time.Duration.between(java.time.Instant.now(), ctx.deadline())
                        .toMillis() < timeoutMs;
    }

    private void record(RunContext ctx, ToolResult envelope) {
        if (ctx != null && ctx.runId() != null) {
            lastResults.put(ctx.runId(), envelope);
        }
    }

    /**
     * The typed envelope of the last call under this runId — audits and
     * tests consume the classification instead of parsing strings.
     */
    public ToolResult lastResult(String runId) {
        return lastResults.get(runId);
    }

    /** Package-visible for tests: inject a shared watchdog pool. */
    static ContractAwareToolExecutor withWatchdog(ToolRegistry registry, ToolExecutor delegate,
                                                   ExecutorService watchdog) {
        return new ContractAwareToolExecutor(registry, delegate, watchdog, false);
    }
}

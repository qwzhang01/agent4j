package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.ResumeToken;
import io.github.qwzhang01.agent.workflow.runtime.Run;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import io.github.qwzhang01.agent.workflow.runtime.TimeoutPolicy;
import io.github.qwzhang01.agent.workflow.runtime.durable.SideEffectLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The interpreter: walks a Workflow from START to END.
 * <p>
 * Main loop (one iteration = one node):
 * 1. [Stage 6] Check cancellation and max-steps
 * 2. Route: explicit node jump > matching conditional edge > unconditional edge
 * 3. Execute node (with RetryPolicy), on failure try onError edge
 * 4. [Stage 6] Catch PauseException -> save cursor, return PAUSED
 * 5. Write output to the blackboard under the node id
 * 6. Record a StepRecord in the trace
 * 7. Advance cursor
 * <p>
 * Stage 6 additions:
 * - {@link #execute(Run)}: main entry point with pause/cancel/resume support
 * - Resume: if Run has a cursor, start from there (skip completed nodes)
 * - Cancel: check volatile flag at each node boundary
 * - Timeout: run-level check at each boundary; node-level wait around execute
 * - Pause: catch PauseException, save cursor = paused node, return PAUSED
 */
public class GraphRuntime {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);
    public static final int DEFAULT_MAX_STEPS = 25;
    private int maxSteps = DEFAULT_MAX_STEPS;

    /** Stage 7: scheduler passed to nodes via NodeContext (null in Stage 5-6). */
    private Object scheduler;

    /**
     * Optional side-effect ledger. When set and the Run has a runId, a
     * successful node consults the ledger before executing and records
     * after: a hit replays the stored result so crash-replay does not
     * re-touch the outside world. Null = current behavior (no ledger).
     */
    private SideEffectLedger ledger;

    static String summarize(Object output) {
        if (output == null) {
            return "null";
        }
        var s = String.valueOf(output);
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    // ============ Configuration ============

    public GraphRuntime maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /** Stage 7: set the scheduler, available to nodes via ctx.scheduler(). */
    public GraphRuntime scheduler(Object scheduler) {
        this.scheduler = scheduler;
        return this;
    }

    /** Wire the durability ledger into the node execute path. */
    public GraphRuntime sideEffectLedger(SideEffectLedger ledger) {
        this.ledger = ledger;
        return this;
    }

    // ============ Stage 5 Compat (no pause/cancel) ============

    /**
     * Run with a fresh WorkflowState. Delegates to {@link #execute(Run)}
     * with an anonymous Run (null runId = no RunManager, nodes use sync mode).
     */
    public ExecutionResult run(Workflow workflow, WorkflowState state) {
        Run run = new Run(null, workflow, state);
        return execute(run);
    }

    /**
     * Convenience: run with a fresh WorkflowState carrying the given input.
     */
    public ExecutionResult run(Workflow workflow, Object input) {
        return run(workflow, WorkflowState.of(input));
    }

    // ============ Stage 6: Execute with Run (pause/cancel/resume) ============

    /**
     * Execute (or resume) a Run. This is the main entry point for
     * {@link io.github.qwzhang01.agent.workflow.runtime.RunManager}.
     */
    public ExecutionResult execute(Run run) {
        try {
            return doExecute(run);
        } catch (Exception e) {
            log.error("[{}] Runtime error: {}", run.getRunId(), e.getMessage(), e);
            // Stage 3 hardening: message first, status second - FAILED is
            // the publication point for cross-thread observers.
            run.setErrorMessage(e.getMessage());
            run.setStatus(RunState.FAILED);
            return ExecutionResult.failed("Runtime error: " + e.getMessage(), run.getState());
        }
    }

    // ============ Main Loop ============

    private ExecutionResult doExecute(Run run) throws Exception {
        Workflow workflow = run.getWorkflow();
        WorkflowState state = run.getState();
        long executeStarted = System.currentTimeMillis();
        TimeoutPolicy timeout = run.getTimeoutPolicy();

        // Resume vs fresh start
        boolean resuming = run.getCursor() != null;
        String cursor = resuming
                ? run.getCursor()
                : route(workflow, Workflow.START, null, state);
        Object lastOutput = resuming ? run.getPendingInput() : state.getInput();
        int steps = run.getStepsExecuted();

        log.info("[{}] {} workflow '{}'", run.getRunId(),
                resuming ? "Resuming" : "Starting", workflow.name());

        // Stage 1.4 (harness roadmap): unified deadline from the run context
        // (structured TIMEOUT, in addition to TimeoutPolicy's string path).
        RunContext runCtx = run.getRunContext();

        while (!Workflow.END.equals(cursor)) {
            // --------------------------------------------
            // [Stage 6] Cancel check (cooperative)
            // --------------------------------------------
            if (run.isCancelled()) {
                state.record(StepRecord.cancelled(cursor));
                run.setStatus(RunState.CANCELLED);
                log.info("[{}] Cancelled at node '{}'", run.getRunId(), cursor);
                return ExecutionResult.cancelled(state);
            }

            // Stage 1.4: unified deadline (structured TIMEOUT classification;
            // the message carries the marker "[TIMEOUT]" so consumers can
            // classify without parsing free text - full FailureKind mapping
            // lands with Stage 2.2's ExecutionResult extension).
            if (runCtx != null && runCtx.isDeadlineExceeded()) {
                String msg = "[TIMEOUT] Run deadline exceeded at node '" + cursor + "'";
                run.setErrorMessage(msg);
                run.setStatus(RunState.FAILED);
                state.record(StepRecord.failed(cursor, 0, 0, msg));
                log.info("[{}] {}", run.getRunId(), msg);
                return ExecutionResult.failed(msg, state);
            }

            ExecutionResult timedOut = failIfRunTimedOut(run, state, cursor, executeStarted, timeout);
            if (timedOut != null) {
                return timedOut;
            }

            // --------------------------------------------
            // Max steps guard (preserved from Stage 5)
            // --------------------------------------------
            if (++steps > maxSteps) {
                String msg = "Max steps (" + maxSteps + ") exceeded at node '" + cursor
                        + "' - possible cycle in the graph";
                run.setErrorMessage(msg);
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed(msg, state);
            }

            WorkflowNode node = workflow.node(cursor);
            if (node == null) {
                String msg = "Unknown node: '" + cursor + "'";
                run.setErrorMessage(msg);
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed(msg, state);
            }

            // --------------------------------------------
            // Ledger hit: replay instead of re-executing
            // --------------------------------------------
            String runId = run.getRunId();
            if (ledger != null && runId != null && !runId.isBlank()) {
                java.util.Optional<SideEffectLedger.Effect> hit = ledger.lookup(runId, cursor);
                if (hit.isPresent()) {
                    Object replayed = hit.get().result();
                    state.put(node.id(), replayed);
                    state.record(StepRecord.success(node.id(), 0, 1,
                            summarize(replayed) + " [ledger-replay]",
                            System.currentTimeMillis(), System.currentTimeMillis()));
                    lastOutput = replayed;
                    log.info("[{}] Node '{}' replayed from side-effect ledger", runId, cursor);
                    cursor = route(workflow, node.id(), null, state);
                    continue;
                }
            }

            // --------------------------------------------
            // Execute node (with retry, catch pause)
            // --------------------------------------------
            NodeContext ctx = NodeContext.of(state, lastOutput, run.getRunId(), resuming,
                    scheduler, run.getRunContext());
            resuming = false;  // only the first node (resume target) gets isResuming=true

            long nodeStart = System.currentTimeMillis();
            ExecOutcome outcome;
            try {
                outcome = executeWithRetry(workflow, node, ctx, timeout);
            } catch (PauseException pe) {
                // Node requested pause: save cursor = this node (will re-execute on resume)
                run.setCursor(cursor);
                run.setPendingInput(lastOutput);
                run.setStepsExecuted(steps);
                run.setStatus(RunState.PAUSED);
                state.record(StepRecord.paused(cursor, pe.getMessage(),
                        nodeStart, System.currentTimeMillis()));
                log.info("[{}] Paused at node '{}': {}", run.getRunId(), cursor, pe.getMessage());
                return ExecutionResult.paused(
                        new ResumeToken(run.getRunId(), null, cursor), state);
            } catch (NodeTimeoutException te) {
                run.setErrorMessage(te.getMessage());
                run.setStatus(RunState.FAILED);
                state.record(StepRecord.failed(cursor, 0, 0, te.getMessage()));
                log.info("[{}] {}", run.getRunId(), te.getMessage());
                return ExecutionResult.failed(te.getMessage(), state);
            }

            // --------------------------------------------
            // Failure handling (preserved from Stage 5)
            // --------------------------------------------
            if (outcome.failure() != null) {
                state.record(StepRecord.failed(node.id(), outcome.durationMs(),
                        outcome.attempts(), outcome.failure().getMessage(),
                        nodeStart, System.currentTimeMillis()));
                log.warn("[{}] Node '{}' failed after {} attempt(s): {}",
                        run.getRunId(), node.id(), outcome.attempts(), outcome.failure().getMessage());

                List<Edge> errEdges = workflow.errorEdges(node.id());
                if (!errEdges.isEmpty()) {
                    Edge err = errEdges.get(0);
                    log.info("[{}] Node '{}' failure routed via onError edge to '{}'",
                            run.getRunId(), node.id(), err.to());
                    lastOutput = outcome.failure().getMessage();
                    cursor = err.to();
                    continue;
                }
                String msg = "Node '" + node.id() + "' failed after "
                        + outcome.attempts() + " attempt(s): " + outcome.failure().getMessage();
                run.setErrorMessage(msg);
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed(msg, state);
            }

            // --------------------------------------------
            // Success: write blackboard, record trace, advance
            // --------------------------------------------
            NodeResult result = outcome.result();
            state.put(node.id(), result.output());
            state.record(StepRecord.success(node.id(), outcome.durationMs(),
                    outcome.attempts(), summarize(result.output()),
                    nodeStart, System.currentTimeMillis()));
            lastOutput = result.output();
            recordLedger(runId, node.id(), result.output());

            timedOut = failIfRunTimedOut(run, state, cursor, executeStarted, timeout);
            if (timedOut != null) {
                return timedOut;
            }

            cursor = route(workflow, node.id(), result.next(), state);
        }

        ExecutionResult timedOutAtEnd = failIfRunTimedOut(run, state, Workflow.END, executeStarted, timeout);
        if (timedOutAtEnd != null) {
            return timedOutAtEnd;
        }

        run.setStatus(RunState.SUCCEEDED);
        log.info("[{}] Completed in {} step(s), {} trace record(s)",
                run.getRunId(), steps, state.getTrace().size());
        return ExecutionResult.success(lastOutput, state);
    }

    private void recordLedger(String runId, String nodeId, Object output) {
        if (ledger == null || runId == null || runId.isBlank()) {
            return;
        }
        String rendered = output == null ? "null" : String.valueOf(output);
        // Node-scoped row: empty argsHash so JDBC lookup(runId, nodeId) hits
        // the same row InMemory finds by Effect.idFor(runId, nodeId).
        ledger.record(new SideEffectLedger.Effect(
                SideEffectLedger.Effect.idFor(runId, nodeId),
                runId, nodeId, "", "",
                SideEffectLedger.DeliverySemantics.AT_MOST_ONCE,
                SideEffectLedger.RetryDisposition.NOT_RETRYABLE,
                rendered, System.currentTimeMillis()));
    }

    // ============ Node Execution (retry wrapper) ============

    private ExecOutcome executeWithRetry(Workflow workflow, WorkflowNode node, NodeContext ctx,
                                         TimeoutPolicy timeout) throws PauseException {
        RetryPolicy policy = workflow.retryPolicyFor(node.id());
        long start = System.currentTimeMillis();
        Exception failure = null;

        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(policy.delayForAttempt(attempt - 1));
            }
            try {
                NodeResult result = executeNode(node, ctx, timeout);
                return ExecOutcome.ok(result, System.currentTimeMillis() - start, attempt + 1);
            } catch (PauseException pe) {
                // Propagate immediately - pause is not a failure, don't retry
                throw pe;
            } catch (NodeTimeoutException te) {
                // Timeout is not retryable and does not take onError routes
                throw te;
            } catch (Exception e) {
                failure = e;
                log.debug("[{}] Node '{}' attempt {} failed: {}",
                        node.id(), node.id(), attempt + 1, e.getMessage());
            }
        }
        return ExecOutcome.error(failure, System.currentTimeMillis() - start, policy.maxRetries() + 1);
    }

    private NodeResult executeNode(WorkflowNode node, NodeContext ctx, TimeoutPolicy timeout)
            throws Exception {
        long nodeLimit = timeout.nodeTimeoutMs();
        if (nodeLimit <= 0) {
            return node.execute(ctx);
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return node.execute(ctx);
                } catch (PauseException pe) {
                    throw new CompletionException(pe);
                } catch (RuntimeException re) {
                    throw re;
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }).orTimeout(nodeLimit, TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof TimeoutException) {
                throw new NodeTimeoutException(node.id(), nodeLimit);
            }
            if (cause instanceof PauseException pe) {
                throw pe;
            }
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private ExecutionResult failIfRunTimedOut(Run run, WorkflowState state, String cursor,
                                              long executeStarted, TimeoutPolicy timeout) {
        if (!timeout.isRunTimedOut(executeStarted)) {
            return null;
        }
        String msg = "Run timed out after " + timeout.runTimeoutMs() + "ms at node '" + cursor + "'";
        run.setErrorMessage(msg);
        run.setStatus(RunState.FAILED);
        state.record(StepRecord.failed(cursor, 0, 0, msg));
        log.info("[{}] {}", run.getRunId(), msg);
        return ExecutionResult.failed(msg, state);
    }

    /**
     * Node-level timeout: fail the whole run (no onError routing, no retry).
     */
    static final class NodeTimeoutException extends RuntimeException {
        NodeTimeoutException(String nodeId, long timeoutMs) {
            super("Node '" + nodeId + "' timed out after " + timeoutMs + "ms");
        }
    }

    // ============ Routing (preserved from Stage 5) ============

    private String route(Workflow workflow, String from, String explicitNext, WorkflowState state) {
        // 1. Explicit jump takes priority
        if (explicitNext != null) {
            if (!Workflow.END.equals(explicitNext) && !workflow.hasNode(explicitNext)) {
                throw new WorkflowException("Node '" + from + "' jumped to unknown node '" + explicitNext + "'");
            }
            return explicitNext;
        }

        // 2. Conditional edges first - routing must be deterministic
        List<Edge> outgoing = workflow.outgoingEdges(from);
        List<Edge> conditionalMatches = outgoing.stream()
                .filter(e -> e.condition() != null && e.condition().test(state))
                .toList();
        if (conditionalMatches.size() > 1) {
            throw new WorkflowException("Ambiguous routing from '" + from + "': "
                    + conditionalMatches.size() + " conditional edges matched the current state"
                    + " (routing must be deterministic)");
        }
        if (conditionalMatches.size() == 1) {
            return conditionalMatches.get(0).to();
        }

        // 3. No conditional edge matched: fall back to the unconditional edge (otherwise)
        List<Edge> unconditional = outgoing.stream()
                .filter(e -> e.condition() == null)
                .toList();
        if (unconditional.size() > 1) {
            throw new WorkflowException("Ambiguous routing from '" + from + "': "
                    + unconditional.size() + " unconditional edges declared");
        }
        if (!unconditional.isEmpty()) {
            return unconditional.get(0).to();
        }

        throw new WorkflowException("Dead end from '" + from + "': no edge matched the current state");
    }

    // ============ Helpers ============

    private void sleepQuietly(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ExecOutcome(NodeResult result, Exception failure, long durationMs, int attempts) {
        static ExecOutcome ok(NodeResult r, long dur, int attempts) {
            return new ExecOutcome(r, null, dur, attempts);
        }

        static ExecOutcome error(Exception e, long dur, int attempts) {
            return new ExecOutcome(null, e, dur, attempts);
        }
    }
}

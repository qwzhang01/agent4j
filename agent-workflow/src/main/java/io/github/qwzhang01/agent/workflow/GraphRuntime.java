package io.github.qwzhang01.agent.workflow;

import io.github.qwzhang01.agent.workflow.runtime.PauseException;
import io.github.qwzhang01.agent.workflow.runtime.ResumeToken;
import io.github.qwzhang01.agent.workflow.runtime.Run;
import io.github.qwzhang01.agent.workflow.runtime.RunState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

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
 * - Pause: catch PauseException, save cursor = paused node, return PAUSED
 */
public class GraphRuntime {

    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);
    public static final int DEFAULT_MAX_STEPS = 25;
    private int maxSteps = DEFAULT_MAX_STEPS;

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
            run.setStatus(RunState.FAILED);
            run.setErrorMessage(e.getMessage());
            return ExecutionResult.failed("Runtime error: " + e.getMessage(), run.getState());
        }
    }

    // ============ Main Loop ============

    private ExecutionResult doExecute(Run run) throws Exception {
        Workflow workflow = run.getWorkflow();
        WorkflowState state = run.getState();

        // Resume vs fresh start
        boolean resuming = run.getCursor() != null;
        String cursor = resuming
                ? run.getCursor()
                : route(workflow, Workflow.START, null, state);
        Object lastOutput = resuming ? run.getPendingInput() : state.getInput();
        int steps = run.getStepsExecuted();

        log.info("[{}] {} workflow '{}'", run.getRunId(),
                resuming ? "Resuming" : "Starting", workflow.name());

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

            // --------------------------------------------
            // Max steps guard (preserved from Stage 5)
            // --------------------------------------------
            if (++steps > maxSteps) {
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed(
                        "Max steps (" + maxSteps + ") exceeded at node '" + cursor
                                + "' - possible cycle in the graph", state);
            }

            WorkflowNode node = workflow.node(cursor);
            if (node == null) {
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed("Unknown node: '" + cursor + "'", state);
            }

            // --------------------------------------------
            // Execute node (with retry, catch pause)
            // --------------------------------------------
            NodeContext ctx = NodeContext.of(state, lastOutput, run.getRunId(), resuming);
            resuming = false;  // only the first node (resume target) gets isResuming=true

            ExecOutcome outcome;
            try {
                outcome = executeWithRetry(workflow, node, ctx);
            } catch (PauseException pe) {
                // Node requested pause: save cursor = this node (will re-execute on resume)
                run.setCursor(cursor);
                run.setPendingInput(lastOutput);
                run.setStepsExecuted(steps);
                run.setStatus(RunState.PAUSED);
                state.record(StepRecord.paused(cursor, pe.getMessage()));
                log.info("[{}] Paused at node '{}': {}", run.getRunId(), cursor, pe.getMessage());
                return ExecutionResult.paused(
                        new ResumeToken(run.getRunId(), null, cursor), state);
            }

            // --------------------------------------------
            // Failure handling (preserved from Stage 5)
            // --------------------------------------------
            if (outcome.failure() != null) {
                state.record(StepRecord.failed(node.id(), outcome.durationMs(),
                        outcome.attempts(), outcome.failure().getMessage()));
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
                run.setStatus(RunState.FAILED);
                return ExecutionResult.failed("Node '" + node.id() + "' failed after "
                        + outcome.attempts() + " attempt(s): " + outcome.failure().getMessage(), state);
            }

            // --------------------------------------------
            // Success: write blackboard, record trace, advance
            // --------------------------------------------
            NodeResult result = outcome.result();
            state.put(node.id(), result.output());
            state.record(StepRecord.success(node.id(), outcome.durationMs(),
                    outcome.attempts(), summarize(result.output())));
            lastOutput = result.output();

            cursor = route(workflow, node.id(), result.next(), state);
        }

        run.setStatus(RunState.SUCCEEDED);
        log.info("[{}] Completed in {} step(s), {} trace record(s)",
                run.getRunId(), steps, state.getTrace().size());
        return ExecutionResult.success(lastOutput, state);
    }

    // ============ Node Execution (retry wrapper) ============

    private ExecOutcome executeWithRetry(Workflow workflow, WorkflowNode node, NodeContext ctx)
            throws PauseException {
        RetryPolicy policy = workflow.retryPolicyFor(node.id());
        long start = System.currentTimeMillis();
        Exception failure = null;

        for (int attempt = 0; attempt <= policy.maxRetries(); attempt++) {
            if (attempt > 0) {
                sleepQuietly(policy.delayForAttempt(attempt - 1));
            }
            try {
                NodeResult result = node.execute(ctx);
                return ExecOutcome.ok(result, System.currentTimeMillis() - start, attempt + 1);
            } catch (PauseException pe) {
                // Propagate immediately - pause is not a failure, don't retry
                throw pe;
            } catch (Exception e) {
                failure = e;
                log.debug("[{}] Node '{}' attempt {} failed: {}",
                        node.id(), node.id(), attempt + 1, e.getMessage());
            }
        }
        return ExecOutcome.error(failure, System.currentTimeMillis() - start, policy.maxRetries() + 1);
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

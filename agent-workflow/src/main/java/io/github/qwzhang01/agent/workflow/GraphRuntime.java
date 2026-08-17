package io.github.qwzhang01.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The interpreter: walks a Workflow from START to END.
 * <p>
 * Main loop (one iteration = one node):
 * 1. Route: explicit node jump > matching conditional edge > unconditional edge
 * 2. Execute node (with RetryPolicy), on failure try onError edge
 * 3. Write output to the blackboard under the node id
 * 4. Record a StepRecord in the trace
 * 5. Advance cursor
 * <p>
 * Safety: maxSteps guards against conditional-edge cycles (the workflow
 * counterpart of AgentState.hasStepsRemaining - design decision D7).
 */
public class GraphRuntime {

    public static final int DEFAULT_MAX_STEPS = 25;
    private static final Logger log = LoggerFactory.getLogger(GraphRuntime.class);
    private int maxSteps = DEFAULT_MAX_STEPS;

    static String summarize(Object output) {
        if (output == null) {
            return "null";
        }
        var s = String.valueOf(output);
        return s.length() > 120 ? s.substring(0, 117) + "..." : s;
    }

    // ============ Public API ============

    public GraphRuntime maxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
        return this;
    }

    /**
     * Run the workflow with the given blackboard state.
     */
    public ExecutionResult run(Workflow workflow, WorkflowState state) {
        try {
            return doRun(workflow, state);
        } catch (Exception e) {
            log.error("[{}] Runtime error: {}", workflow.name(), e.getMessage(), e);
            return ExecutionResult.failed("Runtime error: " + e.getMessage(), state);
        }
    }

    // ============ Main Loop ============

    /**
     * Convenience: run with a fresh WorkflowState carrying the given input.
     */
    public ExecutionResult run(Workflow workflow, Object input) {
        return run(workflow, WorkflowState.of(input));
    }

    // ============ Node Execution (retry wrapper) ============

    private ExecutionResult doRun(Workflow workflow, WorkflowState state) {
        Object lastOutput = state.getInput();
        int steps = 0;

        log.info("[{}] Starting workflow, input={}", workflow.name(), summarize(state.getInput()));

        // START is a virtual node: route to the first real node before executing
        String cursor = route(workflow, Workflow.START, null, state);

        while (!Workflow.END.equals(cursor)) {
            if (++steps > maxSteps) {
                return ExecutionResult.failed(
                        "Max steps (" + maxSteps + ") exceeded at node '" + cursor
                                + "' - possible cycle in the graph", state);
            }

            WorkflowNode node = workflow.node(cursor);
            if (node == null) {
                return ExecutionResult.failed("Unknown node: '" + cursor + "'", state);
            }

            NodeContext ctx = NodeContext.of(state, lastOutput);
            ExecOutcome outcome = executeWithRetry(workflow, node, ctx);

            if (outcome.failure() != null) {
                state.record(StepRecord.failed(node.id(), outcome.durationMs(),
                        outcome.attempts(), outcome.failure().getMessage()));
                log.warn("[{}] Node '{}' failed after {} attempt(s): {}",
                        workflow.name(), node.id(), outcome.attempts(), outcome.failure().getMessage());

                // Failure routing: onError edge if declared, else workflow FAILED
                List<Edge> errEdges = workflow.errorEdges(node.id());
                if (!errEdges.isEmpty()) {
                    Edge err = errEdges.get(0);
                    log.info("[{}] Node '{}' failure routed via onError edge to '{}'",
                            workflow.name(), node.id(), err.to());
                    lastOutput = outcome.failure().getMessage();
                    cursor = err.to();
                    continue;
                }
                return ExecutionResult.failed("Node '" + node.id() + "' failed after "
                        + outcome.attempts() + " attempt(s): " + outcome.failure().getMessage(), state);
            }

            NodeResult result = outcome.result();
            state.put(node.id(), result.output());
            state.record(StepRecord.success(node.id(), outcome.durationMs(),
                    outcome.attempts(), summarize(result.output())));
            lastOutput = result.output();

            cursor = route(workflow, node.id(), result.next(), state);
        }

        log.info("[{}] Completed in {} step(s), {} trace record(s)",
                workflow.name(), steps, state.getTrace().size());
        return ExecutionResult.success(lastOutput, state);
    }

    private ExecOutcome executeWithRetry(Workflow workflow, WorkflowNode node, NodeContext ctx) {
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
            } catch (Exception e) {
                failure = e;
                log.debug("[{}] Node '{}' attempt {} failed: {}",
                        node.id(), node.id(), attempt + 1, e.getMessage());
            }
        }
        return ExecOutcome.error(failure, System.currentTimeMillis() - start, policy.maxRetries() + 1);
    }

    // ============ Routing ============

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

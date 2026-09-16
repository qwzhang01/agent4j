package io.github.qwzhang01.agent.workflow.plan;

import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowBuilder;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.WorkflowState;
import io.github.qwzhang01.agent.workflow.nodes.ActionNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Executes a {@link Plan} on the EXISTING workflow runtime (Stage 9:
 * "adding a Planner must not copy a new Runtime").
 * <p>
 * The adapter lowers a Plan to the graph model the runtime already
 * executes. The runtime is a single-cursor deterministic state machine
 * (at most one unconditional edge out of a node, routing must be
 * unambiguous), so a diamond of dependencies cannot lower to fan-out
 * edges. The honest lowering is a TOPOLOGICAL CHAIN: {@link Plan}
 * validates declaration order as topological order at construction, and
 * the chain executes steps in exactly that order — which satisfies every
 * dependency edge by construction. Steps still get their own node ids on
 * the blackboard, checkpoints resume at step granularity, and the
 * runtime's failure semantics apply per step unchanged.
 * <ul>
 *   <li>START → first step, each step → the next declared step, last
 *       step → END (a linear chain, no ambiguity);</li>
 *   <li>the step's own {@code after} deps are checked by Plan at
 *       construction (a step may only depend on earlier steps), so the
 *       chain order IS a valid execution order for the dependency DAG;</li>
 *   <li>version the lowering with the plan identity ({@code "v" +
 *       planVersion}) so a resume against a drifted plan is refused by
 *       the runtime's own DEFINITION_VERSION_MISMATCH.</li>
 * </ul>
 * Parallel execution of independent steps composes on top when needed
 * (a step's executor may fan out via {@code ParallelToolExecutor} or the
 * workflow runtime's own parallel nodes) — the lowering itself stays
 * deterministic.
 */
public final class PlanExecutor {

    /** What one plan step does: read the objective, return an output. */
    @FunctionalInterface
    public interface StepExecutor {
        /**
         * Execute one step. May throw {@code PauseException} to park the
         * plan at this step (durable resume), or any other exception to
         * fail it — the runtime's node semantics apply unchanged.
         */
        String execute(Plan.Step step, Plan plan) throws Exception;
    }

    /**
     * Lower a plan to a runtime workflow. The returned workflow runs on
     * {@code GraphRuntime.run(workflow, input)} directly, or wraps in a
     * Run + RunManager for durable execution.
     *
     * @param plan         validated plan
     * @param stepExecutor executes each step's objective
     */
    public Workflow toWorkflow(Plan plan, StepExecutor stepExecutor) {
        WorkflowBuilder builder = Workflow.builder("plan:" + plan.planId())
                .version("v" + plan.planVersion());

        List<Plan.Step> steps = plan.steps();
        String previous = Workflow.START;
        for (Plan.Step step : steps) {
            WorkflowNode node = ActionNode.of(step.id(),
                    ctx -> stepExecutor.execute(step, plan));
            builder.node(node);
            // Linear chain in declaration (= topological) order. Every
            // declared dependency points to an EARLIER step, so by the
            // time a step runs, all its deps have already run.
            builder.edge(previous, step.id()).otherwise();
            previous = step.id();
        }
        builder.edge(previous, Workflow.END).otherwise();
        return builder.build();
    }

    /**
     * Completed step ids from a (possibly resumed) blackboard: a step is
     * done when the runtime recorded its output under its node id.
     * Executors use this on resume.
     */
    public Set<String> completedFrom(WorkflowState state, Plan plan) {
        Set<String> completed = new HashSet<>();
        for (Plan.Step step : plan.steps()) {
            if (state.get(step.id()) != null) {
                completed.add(step.id());
            }
        }
        return completed;
    }
}

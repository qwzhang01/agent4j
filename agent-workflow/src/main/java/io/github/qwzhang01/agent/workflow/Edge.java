package io.github.qwzhang01.agent.workflow;

import java.util.function.Predicate;

/**
 * A directed edge in the workflow graph.
 * <p>
 * Routing decision is declared here, not inside nodes: looking at the
 * graph definition shows every possible path.
 * <p>
 * Error edges (see WorkflowBuilder#onError) are stored separately in
 * the Workflow; this record only models normal routing.
 *
 * @param from      source node id (or Workflow.START)
 * @param to        target node id (or Workflow.END)
 * @param condition optional predicate over the blackboard; null = unconditional
 */
public record Edge(String from, String to, Predicate<WorkflowState> condition) {

    /**
     * Whether this edge is traversable given the current blackboard state.
     */
    public boolean matches(WorkflowState state) {
        return condition == null || condition.test(state);
    }

    @Override
    public String toString() {
        var cond = condition == null ? "" : " [conditional]";
        return from + " -> " + to + cond;
    }
}

package io.github.qwzhang01.agent.workflow;

/**
 * A node in a workflow graph: the unit of behavior.
 * <p>
 * Design principle: a node does one thing and returns its output.
 * It does NOT decide routing - routing is declared on edges
 * (except explicit jumps via {@link NodeResult#jump}).
 * <p>
 * Implementations may be deterministic (Java logic) or non-deterministic
 * (wrap an LLM agent - see AgentNode). The graph stays deterministic:
 * a node is just a possibly-slow synchronous step to the runtime.
 */
public interface WorkflowNode {

    /**
     * Unique node id within a workflow. Used as the blackboard key
     * for this node's output and as edge endpoints.
     */
    String id();

    /**
     * Execute the node.
     *
     * @param ctx execution context: shared blackboard state + this node's input
     * @return result containing the output (written to blackboard) and optional explicit next node
     * @throws Exception any failure; handled by RetryPolicy / onError edge / workflow failure
     */
    NodeResult execute(NodeContext ctx) throws Exception;
}

package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

/**
 * Deterministic Java-logic node: a lambda is a node.
 * <pre>{@code
 * ActionNode.of("handoff", ctx -> "transferred to human agent")
 * }</pre>
 */
public final class ActionNode implements WorkflowNode {

    /** Node behavior with checked exceptions allowed. */
    @FunctionalInterface
    public interface NodeAction {
        Object execute(NodeContext ctx) throws Exception;
    }

    private final String id;
    private final NodeAction action;

    private ActionNode(String id, NodeAction action) {
        this.id = id;
        this.action = action;
    }

    public static ActionNode of(String id, NodeAction action) {
        return new ActionNode(id, action);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        Object out = action.execute(ctx);
        // Allow lambdas to return a full NodeResult (e.g. explicit jumps)
        return out instanceof NodeResult nr ? nr : NodeResult.of(out);
    }
}

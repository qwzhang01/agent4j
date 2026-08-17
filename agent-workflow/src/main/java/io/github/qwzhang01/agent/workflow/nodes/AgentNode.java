package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.core.agent.Agent;
import io.github.qwzhang01.agent.core.agent.AgentState;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

import java.util.function.Function;

/**
 * Wraps an agent-core {@link Agent} as a workflow node (design decision D2).
 * <p>
 * The Agent runs its own internal ReAct loop (possibly many steps), but to
 * the graph it is just one possibly-slow synchronous step. Uncertainty is
 * contained inside the node; the graph stays deterministic.
 * <p>
 * The node holds one AgentState per instance, so the agent keeps its
 * conversation context across multiple executions of this node within
 * a long-lived workflow (or across runs - share instances deliberately).
 * <p>
 * Not thread-safe: do not share one AgentNode instance between parallel
 * branches; give each branch its own instance.
 */
public final class AgentNode implements WorkflowNode {

    private final String id;
    private final Agent agent;
    private final Function<NodeContext, String> inputFn;
    private AgentState agentState;

    private AgentNode(String id, Agent agent, Function<NodeContext, String> inputFn) {
        this.id = id;
        this.agent = agent;
        this.inputFn = inputFn;
    }

    /**
     * Input defaults to String.valueOf(ctx.input()).
     */
    public static AgentNode of(String id, Agent agent) {
        return new AgentNode(id, agent, null);
    }

    /**
     * Custom input extraction, e.g. from blackboard variables.
     */
    public static AgentNode of(String id, Agent agent, Function<NodeContext, String> inputFn) {
        return new AgentNode(id, agent, inputFn);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        String input = inputFn != null
                ? inputFn.apply(ctx)
                : String.valueOf(ctx.input());
        if (agentState == null) {
            agentState = new AgentState();
        }
        String output = agent.run(input, agentState);
        return NodeResult.of(output);
    }
}

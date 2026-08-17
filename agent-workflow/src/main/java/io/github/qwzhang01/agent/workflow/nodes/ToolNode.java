package io.github.qwzhang01.agent.workflow.nodes;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

import java.util.function.Function;

/**
 * Executes a single agent-core {@link Tool} deterministically:
 * no LLM decides the call - the graph does. Useful for fixed steps
 * (query a system, call an API) inside a workflow.
 * <p>
 * Contrast with AgentNode: there the model decides; here the graph does.
 */
public final class ToolNode implements WorkflowNode {

    private final String id;
    private final Tool tool;
    private final Function<NodeContext, JsonNode> argsFn;

    private ToolNode(String id, Tool tool, Function<NodeContext, JsonNode> argsFn) {
        this.id = id;
        this.tool = tool;
        this.argsFn = argsFn;
    }

    /**
     * No arguments.
     */
    public static ToolNode of(String id, Tool tool) {
        return new ToolNode(id, tool, null);
    }

    /**
     * Arguments extracted from the context, e.g. blackboard variables.
     */
    public static ToolNode of(String id, Tool tool, Function<NodeContext, JsonNode> argsFn) {
        return new ToolNode(id, tool, argsFn);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        JsonNode args = argsFn != null ? argsFn.apply(ctx) : null;
        return NodeResult.of(tool.execute(args));
    }
}

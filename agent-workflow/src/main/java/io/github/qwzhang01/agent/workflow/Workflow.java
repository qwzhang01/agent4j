package io.github.qwzhang01.agent.workflow;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Immutable workflow definition: nodes + edges + retry policies.
 * <p>
 * Design decision (D1): the workflow is DATA, not code. Structure is
 * expressed as POJOs, separated from execution. This is the foundation
 * for Stage 13 (declarative YAML definitions) and DAG visualization.
 * <p>
 * A Workflow can be executed N times with fresh WorkflowStates.
 */
public final class Workflow {

    public static final String START = "__START__";
    public static final String END = "__END__";

    private final String name;
    private final Map<String, WorkflowNode> nodes;
    private final Map<String, List<Edge>> outgoingEdges;
    private final Map<String, List<Edge>> errorEdges;
    private final Map<String, RetryPolicy> retryPolicies;

    Workflow(String name,
             Map<String, WorkflowNode> nodes,
             Map<String, List<Edge>> outgoingEdges,
             Map<String, List<Edge>> errorEdges,
             Map<String, RetryPolicy> retryPolicies) {
        this.name = name;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.outgoingEdges = Collections.unmodifiableMap(outgoingEdges);
        this.errorEdges = Collections.unmodifiableMap(errorEdges);
        this.retryPolicies = Collections.unmodifiableMap(retryPolicies);
    }

    // ============ Accessors ============

    public static WorkflowBuilder builder(String name) {
        return new WorkflowBuilder(name);
    }

    public String name() {
        return name;
    }

    public Map<String, WorkflowNode> nodes() {
        return nodes;
    }

    public WorkflowNode node(String id) {
        return nodes.get(id);
    }

    public boolean hasNode(String id) {
        return nodes.containsKey(id);
    }

    /**
     * Normal routing edges leaving the given node (or START).
     */
    public List<Edge> outgoingEdges(String from) {
        return outgoingEdges.getOrDefault(from, List.of());
    }

    /**
     * Error edges (onError) leaving the given node. Empty = none.
     */
    public List<Edge> errorEdges(String from) {
        return errorEdges.getOrDefault(from, List.of());
    }

    // ============ Builder ============

    /**
     * Retry policy registered for a node; RetryPolicy.NONE by default.
     */
    public RetryPolicy retryPolicyFor(String nodeId) {
        return retryPolicies.getOrDefault(nodeId, RetryPolicy.NONE);
    }
}

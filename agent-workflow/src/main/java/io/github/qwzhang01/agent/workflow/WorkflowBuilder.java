package io.github.qwzhang01.agent.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Fluent builder for {@link Workflow}.
 * <p>
 * Usage:
 * <pre>{@code
 * Workflow wf = Workflow.builder("support-flow")
 *     .node(AgentNode.of("intent", intentAgent))
 *     .node(ToolNode.of("query", ticketTool))
 *     .edge(Workflow.START, "intent")
 *     .edge("intent", "query").when(s -> "QUERY".equals(s.get("intent")))
 *     .edge("intent", Workflow.END).otherwise()
 *     .onError("query", "fallback")
 *     .node("fallback", ActionNode.of("fallback", ctx -> "fallback done"))
 *     .edge("fallback", Workflow.END)
 *     .build();
 * }</pre>
 * <p>
 * build() validates the definition and fails fast on:
 * duplicate node ids, unknown edge endpoints, missing START edges,
 * nodes without outgoing edges, retry policies for unknown nodes.
 */
public final class WorkflowBuilder {

    private final String name;
    private final Map<String, WorkflowNode> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Edge> errorEdges = new ArrayList<>();
    private final Map<String, RetryPolicy> retryPolicies = new LinkedHashMap<>();
    private final List<EdgeSpec> pendingSpecs = new ArrayList<>();

    WorkflowBuilder(String name) {
        this.name = name;
    }

    // ============ Nodes ============

    public WorkflowBuilder node(WorkflowNode node) {
        return node(node, null);
    }

    /** Register a node with a node-level retry policy. */
    public WorkflowBuilder node(WorkflowNode node, RetryPolicy retryPolicy) {
        if (nodes.put(node.id(), node) != null) {
            throw new WorkflowException("Duplicate node id: '" + node.id() + "'");
        }
        if (retryPolicy != null) {
            retryPolicies.put(node.id(), retryPolicy);
        }
        return this;
    }

    // ============ Edges ============

    /**
     * Start declaring an edge. Call .when(predicate) for a conditional
     * edge, .otherwise() for an unconditional edge, or neither (the edge
     * becomes unconditional at build time).
     */
    public EdgeSpec edge(String from, String to) {
        var spec = new EdgeSpec(from, to);
        pendingSpecs.add(spec);
        return spec;
    }

    /** Unconditional error edge: taken when the node fails and retries are exhausted. */
    public WorkflowBuilder onError(String from, String to) {
        errorEdges.add(new Edge(from, to, null));
        return this;
    }

    // ============ Build ============

    public Workflow build() {
        // Resolve edges declared without when()/otherwise()
        for (EdgeSpec spec : pendingSpecs) {
            if (!spec.resolved) {
                edges.add(new Edge(spec.from, spec.to, null));
            }
        }

        validate();

        Map<String, List<Edge>> outgoing = new LinkedHashMap<>();
        for (Edge e : edges) {
            outgoing.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }
        Map<String, List<Edge>> errors = new LinkedHashMap<>();
        for (Edge e : errorEdges) {
            errors.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e);
        }
        return new Workflow(name, nodes, outgoing, errors, retryPolicies);
    }

    // ============ Validation ============

    private void validate() {
        if (nodes.isEmpty()) {
            throw new WorkflowException("Workflow '" + name + "' has no nodes");
        }
        for (Edge e : edges) {
            validateEndpoint(e, false);
        }
        for (Edge e : errorEdges) {
            validateEndpoint(e, true);
        }
        if (edges.stream().noneMatch(e -> Workflow.START.equals(e.from()))) {
            throw new WorkflowException("Workflow '" + name + "': no edge from START");
        }
        for (String nodeId : nodes.keySet()) {
            boolean hasNormalOutgoing = edges.stream()
                    .anyMatch(e -> nodeId.equals(e.from()));
            if (!hasNormalOutgoing) {
                throw new WorkflowException(
                        "Node '" + nodeId + "' has no outgoing edge (dead end at build time)");
            }
        }
        for (String nodeId : retryPolicies.keySet()) {
            if (!nodes.containsKey(nodeId)) {
                throw new WorkflowException("Retry policy references unknown node: '" + nodeId + "'");
            }
        }
    }

    private void validateEndpoint(Edge e, boolean isErrorEdge) {
        var kind = isErrorEdge ? "onError edge" : "edge";
        if (!Workflow.START.equals(e.from()) && !nodes.containsKey(e.from())) {
            throw new WorkflowException("Invalid " + kind + " from unknown node: '" + e.from() + "'");
        }
        if (!Workflow.END.equals(e.to()) && !nodes.containsKey(e.to())) {
            throw new WorkflowException("Invalid " + kind + " to unknown node: '" + e.to() + "'");
        }
    }

    // ============ EdgeSpec ============

    /**
     * Pending edge declaration returned by {@link #edge(String, String)}.
     * <p>
     * Call when()/otherwise() to make the edge conditional/unconditional,
     * or keep chaining builder methods directly - the edge resolves to
     * unconditional at build time.
     */
    public final class EdgeSpec {

        private final String from;
        private final String to;
        private boolean resolved = false;

        private EdgeSpec(String from, String to) {
            this.from = from;
            this.to = to;
        }

        /** Conditional edge: traversable when the predicate matches the blackboard. */
        public WorkflowBuilder when(Predicate<WorkflowState> condition) {
            edges.add(new Edge(from, to, condition));
            resolved = true;
            return WorkflowBuilder.this;
        }

        /** Unconditional edge (fallthrough / default route). */
        public WorkflowBuilder otherwise() {
            edges.add(new Edge(from, to, null));
            resolved = true;
            return WorkflowBuilder.this;
        }

        // ---- Delegated builder methods (this spec stays pending = unconditional) ----

        public EdgeSpec edge(String from, String to) {
            return WorkflowBuilder.this.edge(from, to);
        }

        public WorkflowBuilder node(WorkflowNode node) {
            return WorkflowBuilder.this.node(node);
        }

        public WorkflowBuilder node(WorkflowNode node, RetryPolicy retryPolicy) {
            return WorkflowBuilder.this.node(node, retryPolicy);
        }

        public WorkflowBuilder onError(String from, String to) {
            return WorkflowBuilder.this.onError(from, to);
        }

        public Workflow build() {
            return WorkflowBuilder.this.build();
        }
    }
}

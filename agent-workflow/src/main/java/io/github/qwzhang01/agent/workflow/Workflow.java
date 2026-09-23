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
    private final String version;
    private final Map<String, WorkflowNode> nodes;
    private final Map<String, List<Edge>> outgoingEdges;
    private final Map<String, List<Edge>> errorEdges;
    private final Map<String, RetryPolicy> retryPolicies;

    /** Lazily computed structural fingerprint (volatile: compute once). */
    private volatile String fingerprint;

    Workflow(String name,
             Map<String, WorkflowNode> nodes,
             Map<String, List<Edge>> outgoingEdges,
             Map<String, List<Edge>> errorEdges,
             Map<String, RetryPolicy> retryPolicies) {
        this(name, "", nodes, outgoingEdges, errorEdges, retryPolicies);
    }

    Workflow(String name, String version,
             Map<String, WorkflowNode> nodes,
             Map<String, List<Edge>> outgoingEdges,
             Map<String, List<Edge>> errorEdges,
             Map<String, RetryPolicy> retryPolicies) {
        this.name = name;
        this.version = version == null ? "" : version;
        this.nodes = Collections.unmodifiableMap(nodes);
        this.outgoingEdges = Collections.unmodifiableMap(outgoingEdges);
        this.errorEdges = Collections.unmodifiableMap(errorEdges);
        this.retryPolicies = Collections.unmodifiableMap(retryPolicies);
    }

    public static WorkflowBuilder builder(String name) {
        return new WorkflowBuilder(name);
    }

    public String name() {
        return name;
    }

    /**
     * Stage 3.1 (harness roadmap): definition version, free-form
     * ("" = unversioned legacy definition).
     */
    public String version() {
        return version;
    }

    /**
     * Stage 3.1: structural fingerprint — SHA-256 prefix over the sorted
     * node ids, edge triples (from/condition?/to) and retry policies. Two
     * definitions with the same fingerprint walk identically; a changed
     * graph or retry policy changes the fingerprint, so a resume against
     * a modified definition is detectable and refusable
     * ({@code DEFINITION_VERSION_MISMATCH}), never silent.
     */
    public String fingerprint() {
        String fp = fingerprint;
        if (fp == null) {
            fp = computeFingerprint();
            this.fingerprint = fp;
        }
        return fp;
    }

    private String computeFingerprint() {
        StringBuilder sb = new StringBuilder();
        sb.append("nodes:");
        new java.util.TreeSet<>(nodes.keySet()).forEach(n -> sb.append(n).append(','));
        sb.append(";edges:");
        new java.util.TreeSet<>(outgoingEdges.keySet()).forEach(from -> {
            outgoingEdges.get(from).stream()
                    .map(e -> e.condition() != null)
                    .sorted()
                    .forEach(cond -> sb.append(from).append(cond ? "?>" : "->")
                            .append(firstTargetOf(from, cond)).append(','));
        });
        sb.append(";err:");
        new java.util.TreeSet<>(errorEdges.keySet()).forEach(from ->
                sb.append(from).append('!').append(errorEdges.get(from).size()).append(','));
        sb.append(";retry:");
        new java.util.TreeSet<>(retryPolicies.keySet()).forEach(n -> {
            RetryPolicy p = retryPolicies.get(n);
            sb.append(n).append('=').append(p.maxRetries()).append('/').append(p.initialBackoffMs()).append(',');
        });
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String firstTargetOf(String from, boolean conditional) {
        List<Edge> edges = outgoingEdges.get(from).stream()
                .filter(e -> (e.condition() != null) == conditional)
                .toList();
        return edges.isEmpty() ? "?" : edges.get(0).to();
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

    /**
     * Retry policy registered for a node; RetryPolicy.NONE by default.
     */
    public RetryPolicy retryPolicyFor(String nodeId) {
        return retryPolicies.getOrDefault(nodeId, RetryPolicy.NONE);
    }
}

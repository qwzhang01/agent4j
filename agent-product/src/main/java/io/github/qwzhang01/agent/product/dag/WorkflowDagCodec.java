package io.github.qwzhang01.agent.product.dag;

import io.github.qwzhang01.agent.workflow.Edge;
import io.github.qwzhang01.agent.workflow.Workflow;
import io.github.qwzhang01.agent.workflow.WorkflowBuilder;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.WorkflowState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Workflow &lt;-&gt; DagSpec round-trip codec (Stage 13 M13.5, D5).
 * <p>
 * Same semantics, two representations: the Workflow is the executable truth
 * (immutable definition, "define once, execute N times" from Stage 5), the
 * DagSpec is the serializable projection (what frontends render and editors
 * submit). Round-trip contract:
 * <ul>
 *   <li><b>Complete:</b> node ids + types, every normal and error edge, and
 *       condition NAMES travel across (predicates resolve via
 *       {@link ConditionRegistry} both ways)</li>
 *   <li><b>Honest:</b> an edge whose predicate is not registered refuses to
 *       export - a silently dropped conditional branch would change routing
 *       behavior without a trace</li>
 *   <li><b>Not carried in v1:</b> RetryPolicies, node behavior/refs (nodes are
 *       resolved by id through a resolver function - the D1 registry idea)</li>
 * </ul>
 */
public final class WorkflowDagCodec {

    /**
     * Project a workflow into its serializable DAG description.
     *
     * @param workflow   the executable truth
     * @param conditions registry mapping predicates to exportable names
     * @throws IllegalArgumentException if any conditional edge's predicate is
     *                                  not registered (fail-fast, D5)
     */
    public DagSpec toDag(Workflow workflow, ConditionRegistry conditions) {
        Objects.requireNonNull(workflow, "workflow must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");

        List<DagSpec.NodeSpec> nodes = new ArrayList<>();
        for (WorkflowNode node : workflow.nodes().values()) {
            nodes.add(new DagSpec.NodeSpec(node.id(), node.getClass().getSimpleName()));
        }

        List<DagSpec.EdgeSpec> edges = new ArrayList<>();
        Set<String> edgeSources = new LinkedHashSet<>(workflow.nodes().keySet());
        edgeSources.add(Workflow.START);
        for (String from : edgeSources) {
            for (Edge edge : workflow.outgoingEdges(from)) {
                edges.add(new DagSpec.EdgeSpec(edge.from(), edge.to(),
                        conditionName(edge, conditions, workflow)));
            }
        }

        List<DagSpec.EdgeSpec> errorEdges = new ArrayList<>();
        for (String from : workflow.nodes().keySet()) {
            for (Edge edge : workflow.errorEdges(from)) {
                errorEdges.add(new DagSpec.EdgeSpec(edge.from(), edge.to(),
                        conditionName(edge, conditions, workflow)));
            }
        }

        return new DagSpec("v1", workflow.name(), nodes, edges, errorEdges);
    }

    /**
     * Rebuild a workflow from a DAG description. Node BEHAVIOR comes from the
     * resolver (id -&gt; live node); structure and conditions come from the spec.
     *
     * @param spec         the DAG description
     * @param nodeResolver supplies node instances by id (the D1 registry end)
     * @param conditions   resolves condition names back to predicates
     * @return a rebuilt executable workflow
     * @throws IllegalArgumentException on unknown node, unknown condition name,
     *                                  or invalid graph (builder validates)
     */
    public Workflow fromDag(DagSpec spec,
                            Function<String, WorkflowNode> nodeResolver,
                            ConditionRegistry conditions) {
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(nodeResolver, "nodeResolver must not be null");
        Objects.requireNonNull(conditions, "conditions must not be null");

        WorkflowBuilder builder = Workflow.builder(spec.name());
        for (DagSpec.NodeSpec nodeSpec : spec.nodes()) {
            WorkflowNode node = nodeResolver.apply(nodeSpec.id());
            if (node == null) {
                throw new IllegalArgumentException(
                        "No node instance for id '" + nodeSpec.id() + "' (resolver returned null)");
            }
            builder.node(node);
        }

        for (DagSpec.EdgeSpec edge : spec.edges()) {
            if (edge.when() == null) {
                builder.edge(edge.from(), edge.to());
            } else {
                Predicate<WorkflowState> predicate = conditions.predicateOf(edge.when());
                if (predicate == null) {
                    throw new IllegalArgumentException(
                            "Condition '" + edge.when() + "' is not registered, registered: "
                                    + conditions.names());
                }
                builder.edge(edge.from(), edge.to()).when(predicate);
            }
        }
        for (DagSpec.EdgeSpec edge : spec.errorEdges()) {
            builder.onError(edge.from(), edge.to());
        }
        return builder.build();
    }

    // --------------------------------------------
    // Internals
    // --------------------------------------------

    private String conditionName(Edge edge, ConditionRegistry conditions, Workflow workflow) {
        if (edge.condition() == null) {
            return null;
        }
        String name = conditions.nameOf(edge.condition());
        if (name == null) {
            throw new IllegalArgumentException(
                    "Workflow '" + workflow.name() + "' edge " + edge.from() + " -> " + edge.to()
                            + " carries an unregistered condition predicate - register it in the "
                            + "ConditionRegistry before exporting (refusing to silently drop a "
                            + "routing branch)");
        }
        return name;
    }
}

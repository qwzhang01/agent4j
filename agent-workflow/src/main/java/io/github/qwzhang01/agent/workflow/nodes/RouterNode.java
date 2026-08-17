package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

import java.util.function.Function;

/**
 * Code-driven router: computes a route key and writes it to the blackboard
 * under the node id. Downstream edges compare against that key with simple
 * equality conditions.
 * <p>
 * Use when routing logic is too complex for edge predicates
 * (> 5-6 branches would clutter the graph). The complementary style -
 * plain conditional edges - remains the default (design decision D4).
 * <pre>{@code
 * .node(RouterNode.of("route", ctx -> switch (intentOf(ctx)) {
 *     case "A" -> "branchA";
 *     default -> "fallback";
 * }))
 * .edge("route", "branchA").when(s -> "branchA".equals(s.get("route")))
 * }</pre>
 */
public final class RouterNode implements WorkflowNode {

    private final String id;
    private final Function<NodeContext, String> router;

    private RouterNode(String id, Function<NodeContext, String> router) {
        this.id = id;
        this.router = router;
    }

    public static RouterNode of(String id, Function<NodeContext, String> router) {
        return new RouterNode(id, router);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        return NodeResult.of(router.apply(ctx));
    }
}

package io.github.qwzhang01.agent.workflow.nodes;

import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.StepRecord;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Fork-join parallel execution inside a single node (design decision D5).
 * <p>
 * Each branch is a sequence of nodes executed sequentially on its own
 * future (common ForkJoinPool). Branch node outputs are written to the
 * shared blackboard under their node ids - keep keys distinct per branch
 * by convention (branch-prefixed node ids).
 * <p>
 * The graph structure shows no parallelism (observability trade-off,
 * accepted for v1); upgrading to graph-level parallel scheduling is a
 * v2 concern.
 * <pre>{@code
 * ParallelNode.builder("fanout")
 *     .branch("search", searchNode)
 *     .branch("calc", calcStep1, calcStep2)
 *     .join(JoinPolicy.ALL_OF)
 *     .build()
 * }</pre>
 * <p>
 * Failure semantics: a failing branch makes the whole node fail
 * (unwrapped from CompletionException), then node-level RetryPolicy /
 * onError edges apply as usual.
 */
public final class ParallelNode implements WorkflowNode {

    private final String id;
    private final JoinPolicy joinPolicy;
    private final Map<String, List<WorkflowNode>> branches;

    private ParallelNode(Builder builder) {
        this.id = builder.id;
        this.joinPolicy = builder.joinPolicy;
        this.branches = Map.copyOf(builder.branches);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        Map<String, CompletableFuture<Object>> futures = new LinkedHashMap<>();
        for (var entry : branches.entrySet()) {
            futures.put(entry.getKey(), CompletableFuture.supplyAsync(
                    () -> runBranch(entry.getKey(), entry.getValue(), ctx)));
        }

        try {
            if (joinPolicy == JoinPolicy.ANY_OF) {
                Object first = CompletableFuture.anyOf(
                        futures.values().toArray(CompletableFuture[]::new)).join();
                return NodeResult.of(first);
            }

            CompletableFuture.allOf(
                    futures.values().toArray(CompletableFuture[]::new)).join();
            Map<String, Object> outputs = new LinkedHashMap<>();
            futures.forEach((name, f) -> outputs.put(name, f.join()));
            return NodeResult.of(outputs);
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Run one branch sequentially. First node receives the ParallelNode's
     * own input; each subsequent node receives its predecessor's output.
     */
    private Object runBranch(String branchName, List<WorkflowNode> branchNodes, NodeContext ctx) {
        Object output = ctx.input();
        for (WorkflowNode node : branchNodes) {
            long start = System.currentTimeMillis();
            try {
                NodeContext branchCtx = NodeContext.of(ctx.state(), output);
                output = node.execute(branchCtx).output();
                ctx.state().put(node.id(), output);
                ctx.state().record(StepRecord.success(node.id(),
                        System.currentTimeMillis() - start, 1,
                        String.valueOf(output)));
            } catch (Exception e) {
                ctx.state().record(StepRecord.failed(node.id(),
                        System.currentTimeMillis() - start, 1, e.getMessage()));
                throw new CompletionException("Branch '" + branchName
                        + "' failed at node '" + node.id() + "'", e);
            }
        }
        return output;
    }

    // ============ Builder ============

    public static final class Builder {
        private final String id;
        private final Map<String, List<WorkflowNode>> branches = new LinkedHashMap<>();
        private JoinPolicy joinPolicy = JoinPolicy.ALL_OF;

        private Builder(String id) {
            this.id = id;
        }

        /** Add a branch: one or more nodes run sequentially within the branch. */
        public Builder branch(String name, WorkflowNode first, WorkflowNode... rest) {
            List<WorkflowNode> nodes = new ArrayList<>();
            nodes.add(first);
            nodes.addAll(List.of(rest));
            branches.put(name, nodes);
            return this;
        }

        public Builder join(JoinPolicy joinPolicy) {
            this.joinPolicy = joinPolicy;
            return this;
        }

        public ParallelNode build() {
            if (branches.isEmpty()) {
                throw new IllegalArgumentException("ParallelNode '" + id + "' has no branches");
            }
            return new ParallelNode(this);
        }
    }
}

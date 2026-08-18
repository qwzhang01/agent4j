package io.github.qwzhang01.agent.scheduler.nodes;

import io.github.qwzhang01.agent.scheduler.AsyncTask;
import io.github.qwzhang01.agent.scheduler.TaskPriority;
import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowNode;

import java.util.function.Function;

/**
 * Workflow node that dispatches sub-tasks to the async task queue.
 * <p>
 * Unlike ParallelNode (static parallelism defined in the graph), DispatchTaskNode
 * produces tasks dynamically at runtime. The Agent decides what sub-tasks to
 * dispatch during execution.
 * <p>
 * Usage:
 * <pre>{@code
 * .node(DispatchTaskNode.of("dispatch", ctx -> List.of(
 *     AsyncTask.of(ctx.runId(), "query-A", TaskPriority.HIGH, "search-flow"),
 *     AsyncTask.of(ctx.runId(), "query-B", TaskPriority.NORMAL, "search-flow")
 * )))
 * }</pre>
 * <p>
 * The node does NOT pause - it enqueues tasks and returns immediately.
 * Tasks are consumed by a separate runner (not part of this node).
 */
public final class DispatchTaskNode implements WorkflowNode {

    private final String id;
    private final Function<NodeContext, java.util.List<AsyncTask>> taskProducer;

    private DispatchTaskNode(String id, Function<NodeContext, java.util.List<AsyncTask>> taskProducer) {
        this.id = id;
        this.taskProducer = taskProducer;
    }

    public static DispatchTaskNode of(String id, Function<NodeContext, java.util.List<AsyncTask>> taskProducer) {
        return new DispatchTaskNode(id, taskProducer);
    }

    /** Convenience: dispatch a single task. */
    public static DispatchTaskNode single(String id, String workflowName, TaskPriority priority) {
        return new DispatchTaskNode(id, ctx -> java.util.List.of(
                AsyncTask.of(ctx.runId(), ctx.input(), priority, workflowName)));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) {
        Object sched = ctx.scheduler();
        if (!(sched instanceof TaskScheduler scheduler)) {
            throw new IllegalStateException("DispatchTaskNode requires a TaskScheduler in the context");
        }

        java.util.List<AsyncTask> tasks = taskProducer.apply(ctx);
        for (AsyncTask task : tasks) {
            scheduler.enqueueTask(task);
        }
        return NodeResult.of("dispatched " + tasks.size() + " task(s)");
    }
}

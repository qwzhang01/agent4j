package io.github.qwzhang01.agent.scheduler.nodes;

import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;

import java.time.Duration;

/**
 * Workflow node that waits for an external event before continuing.
 * <p>
 * On first execution: registers an event subscription with the scheduler
 * and throws PauseException to suspend the run.
 * On resume: checks if the event has fired, reads its payload, and passes
 * it through to the next node.
 * <p>
 * Usage:
 * <pre>{@code
 * .node(WaitEventNode.of("wait-ci", "ci-passed:pr-123"))
 * }</pre>
 */
public final class WaitEventNode implements WorkflowNode {

    private final String id;
    private final String eventKey;
    private final Duration timeout;

    private WaitEventNode(String id, String eventKey, Duration timeout) {
        this.id = id;
        this.eventKey = eventKey;
        this.timeout = timeout;
    }

    public static WaitEventNode of(String id, String eventKey) {
        return new WaitEventNode(id, eventKey, null);
    }

    public static WaitEventNode of(String id, String eventKey, Duration timeout) {
        return new WaitEventNode(id, eventKey, timeout);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public NodeResult execute(NodeContext ctx) throws Exception {
        Object sched = ctx.scheduler();
        if (!(sched instanceof TaskScheduler scheduler)) {
            throw new IllegalStateException("WaitEventNode requires a TaskScheduler in the context");
        }

        if (ctx.isResuming()) {
            if (scheduler.hasEventFired(eventKey)) {
                Object payload = scheduler.getEventPayload(eventKey);
                return NodeResult.of(payload != null ? payload : "event:" + eventKey);
            }
            if (scheduler.isEventTimedOut(ctx.runId(), eventKey)) {
                throw new WorkflowException("Event '" + eventKey + "' timed out");
            }
            throw new PauseException(id, "event '" + eventKey + "' not yet fired, re-pausing");
        } else {
            scheduler.waitForEvent(ctx.runId(), eventKey, timeout);
            throw new PauseException(id, "waiting for event '" + eventKey + "'");
        }
    }
}

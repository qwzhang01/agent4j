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
 * The event key can be fixed at graph-definition time, or read from the
 * blackboard (so a previous node can write {@code video-done:{taskId}}).
 */
public final class WaitEventNode implements WorkflowNode {

    public static final String DEFAULT_EVENT_KEY_VARIABLE = "eventKey";

    private final String id;
    private final String eventKey;
    private final String eventKeyVariable;
    private final Duration timeout;

    private WaitEventNode(String id, String eventKey, String eventKeyVariable, Duration timeout) {
        this.id = id;
        this.eventKey = eventKey;
        this.eventKeyVariable = eventKeyVariable;
        this.timeout = timeout;
    }

    public static WaitEventNode of(String id, String eventKey) {
        return new WaitEventNode(id, eventKey, null, null);
    }

    public static WaitEventNode of(String id, String eventKey, Duration timeout) {
        return new WaitEventNode(id, eventKey, null, timeout);
    }

    /**
     * Resolve the event key from {@code state.get(variable)} at runtime.
     */
    public static WaitEventNode fromState(String id, String eventKeyVariable) {
        return fromState(id, eventKeyVariable, null);
    }

    public static WaitEventNode fromState(String id, String eventKeyVariable, Duration timeout) {
        return new WaitEventNode(id, null, eventKeyVariable, timeout);
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

        String key = resolveEventKey(ctx);

        if (ctx.isResuming()) {
            if (scheduler.hasEventFired(key)) {
                Object payload = scheduler.getEventPayload(key);
                return NodeResult.of(payload != null ? payload : "event:" + key);
            }
            if (scheduler.isEventTimedOut(ctx.runId(), key)) {
                throw new WorkflowException("Event '" + key + "' timed out");
            }
            throw new PauseException(id, "event '" + key + "' not yet fired, re-pausing");
        } else {
            scheduler.waitForEvent(ctx.runId(), key, timeout);
            throw new PauseException(id, "waiting for event '" + key + "'");
        }
    }

    private String resolveEventKey(NodeContext ctx) {
        if (eventKeyVariable == null) {
            return eventKey;
        }
        Object value = ctx.state().get(eventKeyVariable);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalStateException(
                    "WaitEventNode '" + id + "' expected state." + eventKeyVariable + " to hold an event key");
        }
        return value.toString();
    }
}

package io.github.qwzhang01.agent.scheduler.nodes;

import io.github.qwzhang01.agent.scheduler.TaskScheduler;
import io.github.qwzhang01.agent.workflow.NodeContext;
import io.github.qwzhang01.agent.workflow.NodeResult;
import io.github.qwzhang01.agent.workflow.WorkflowException;
import io.github.qwzhang01.agent.workflow.WorkflowNode;
import io.github.qwzhang01.agent.workflow.runtime.PauseException;

import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/**
 * Stage 7 dynamic variant: reads the scheduling parameters from the blackboard
 * instead of the constructor, so an upstream AgentNode (LLM) decides at runtime
 * what to wait for and how long.
 * <p>
 * This is what makes scheduling "agent-driven" instead of "developer-driven":
 * the LLM produces an intent (via an upstream AgentNode's structured output),
 * the intent lands on the blackboard, and this node consumes it to register
 * the resume trigger.
 * <p>
 * Expected blackboard intent shape (written by the upstream node):
 * <pre>{@code
 * {"action":"wait_event","event_key":"ci-passed:pr-42"}
 * {"action":"schedule","delay_seconds":7200}
 * }</pre>
 * <p>
 * Governance gate (LLM freedom -> static guardrails): the eventKey must pass
 * the keyValidator before registration (whitelist/format check), and the delay
 * must fall within [minDelay, maxDelay].
 */
public final class DynamicSchedulerNode implements WorkflowNode {

    private final String id;
    /** Blackboard key holding the LLM-produced intent (an upstream node's output). */
    private final String intentKey;
    /** Validates LLM-chosen event keys (default: basic format check). */
    private final Function<String, Boolean> keyValidator;
    private final Duration minDelay;
    private final Duration maxDelay;

    private DynamicSchedulerNode(String id, String intentKey,
                                 Function<String, Boolean> keyValidator,
                                 Duration minDelay, Duration maxDelay) {
        this.id = id;
        this.intentKey = intentKey;
        this.keyValidator = keyValidator;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
    }

    public static DynamicSchedulerNode of(String id, String intentKey) {
        return new DynamicSchedulerNode(id, intentKey,
                DynamicSchedulerNode::defaultKeyCheck,
                Duration.ofSeconds(1), Duration.ofHours(24));
    }

    public static DynamicSchedulerNode of(String id, String intentKey,
                                          Function<String, Boolean> keyValidator,
                                          Duration minDelay, Duration maxDelay) {
        return new DynamicSchedulerNode(id, intentKey, keyValidator, minDelay, maxDelay);
    }

    /** Basic event-key format check (subclass/replace for whitelist enforcement). */
    static boolean defaultKeyCheck(String key) {
        return key != null && key.matches("[a-zA-Z0-9._:-]{1,128}");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NodeResult execute(NodeContext ctx) throws Exception {
        Object sched = ctx.scheduler();
        if (!(sched instanceof TaskScheduler scheduler)) {
            throw new WorkflowException("DynamicSchedulerNode requires a TaskScheduler in the context");
        }

        if (ctx.isResuming()) {
            // Resume path: decide by the registered action.
            Map<String, Object> intent = readIntent(ctx);
            String action = String.valueOf(intent.get("action"));
            if ("wait_event".equals(action)) {
                String eventKey = String.valueOf(intent.get("event_key"));
                if (scheduler.hasEventFired(eventKey)) {
                    Object payload = scheduler.getEventPayload(eventKey);
                    return NodeResult.of(payload != null ? payload : "event:" + eventKey);
                }
                throw new PauseException(id, "event '" + eventKey + "' not yet fired, re-pausing");
            }
            // "schedule": the timer fired (that is why we are resuming) -> pass through
            return NodeResult.of("resumed after " + intent.get("delay_seconds") + "s");
        }

        // First execution: read the LLM-produced intent from the blackboard
        Map<String, Object> intent = readIntent(ctx);
        String action = String.valueOf(intent.get("action"));

        if ("wait_event".equals(action)) {
            String eventKey = String.valueOf(intent.get("event_key"));
            if (!keyValidator.apply(eventKey)) {
                throw new WorkflowException("LLM-chosen event key rejected by policy: '"
                        + eventKey + "'");
            }
            scheduler.waitForEvent(ctx.runId(), eventKey);
            throw new PauseException(id, "waiting for event '" + eventKey + "'");
        }

        if ("schedule".equals(action)) {
            long delaySeconds = asLong(intent.get("delay_seconds"));
            if (delaySeconds < minDelay.toSeconds() || delaySeconds > maxDelay.toSeconds()) {
                throw new WorkflowException("LLM-chosen delay " + delaySeconds
                        + "s out of allowed range [" + minDelay.toSeconds() + "s, "
                        + maxDelay.toSeconds() + "s]");
            }
            scheduler.scheduleResume(ctx.runId(), Duration.ofSeconds(delaySeconds));
            throw new PauseException(id, "scheduled resume in " + delaySeconds + "s");
        }

        throw new WorkflowException("Unknown scheduling action in intent at blackboard key '"
                + intentKey + "': '" + action + "' (expected wait_event | schedule)");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readIntent(NodeContext ctx) {
        Object intent = ctx.state().get(intentKey);
        if (intent == null) {
            throw new WorkflowException("No scheduling intent found on blackboard key '"
                    + intentKey + "' - an upstream node (e.g. AgentNode) must write it first");
        }
        if (intent instanceof Map) {
            return (Map<String, Object>) intent;
        }
        // LLM text output: parse {"action":...} JSON
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(String.valueOf(intent), Map.class);
        } catch (Exception e) {
            throw new WorkflowException("Scheduling intent at '" + intentKey
                    + "' is neither a Map nor valid JSON: " + e.getMessage());
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new WorkflowException("delay_seconds is not numeric: " + value);
        }
    }
}

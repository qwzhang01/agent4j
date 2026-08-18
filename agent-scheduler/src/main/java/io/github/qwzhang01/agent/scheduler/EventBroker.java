package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process event bus for event-driven resume.
 * <p>
 * Design decision (D3): process-local Map, not a message queue. The bus
 * maintains {@code eventKey -> List<runId>} subscriptions. When fire() is
 * called, all subscribed runs are resumed via RunManager.
 * <p>
 * Cross-process events (Kafka/RabbitMQ) are Stage 11 scope.
 */
public class EventBroker {

    private static final Logger log = LoggerFactory.getLogger(EventBroker.class);

    private final RunManager runManager;
    private final Map<String, List<EventTrigger>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Object> eventPayloads = new ConcurrentHashMap<>();

    public EventBroker(RunManager runManager) {
        this.runManager = runManager;
    }

    /**
     * Subscribe a run to an event. When the event fires, the run is resumed.
     */
    public EventTrigger subscribe(EventTrigger trigger) {
        subscriptions.computeIfAbsent(trigger.eventKey(), k -> new CopyOnWriteArrayList<>()).add(trigger);
        log.info("[event] Subscribed run '{}' to event '{}'", trigger.runId(), trigger.eventKey());
        return trigger;
    }

    /**
     * Fire an event without payload. All subscribed runs are resumed.
     */
    public void fire(String eventKey) {
        fire(eventKey, null);
    }

    /**
     * Fire an event with a payload. The payload is stored for resumed nodes
     * to read from the blackboard.
     */
    public void fire(String eventKey, Object payload) {
        if (payload != null) {
            eventPayloads.put(eventKey, payload);
        }
        List<EventTrigger> triggers = subscriptions.remove(eventKey);
        if (triggers == null || triggers.isEmpty()) {
            log.debug("[event] Fire '{}': no subscribers", eventKey);
            return;
        }
        log.info("[event] Fire '{}': resuming {} run(s)", eventKey, triggers.size());
        for (EventTrigger trigger : triggers) {
            try {
                runManager.resume(trigger.runId());
            } catch (Exception e) {
                log.error("[event] Failed to resume run '{}' for event '{}': {}",
                        trigger.runId(), eventKey, e.getMessage());
            }
        }
    }

    /** Get the payload of a fired event (for nodes to read on resume). */
    public Object getPayload(String eventKey) {
        return eventPayloads.get(eventKey);
    }

    /** Check if an event has fired (and payload is available). */
    public boolean hasFired(String eventKey) {
        return eventPayloads.containsKey(eventKey);
    }

    /** List all pending event subscriptions (for debugging / inspection). */
    public Map<String, List<EventTrigger>> getSubscriptions() {
        return Map.copyOf(subscriptions);
    }
}

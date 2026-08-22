package io.github.qwzhang01.agent.scheduler;

import io.github.qwzhang01.agent.workflow.runtime.RunManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process event bus for event-driven resume.
 * <p>
 * Design decision (D3): process-local Map, not a message queue. The bus
 * maintains {@code eventKey -> List<trigger>} subscriptions. When fire() is
 * called, all subscribed runs are resumed via RunManager.
 * <p>
 * Bug fixes over the first version:
 * - hasFired() now tracks fired keys independently of payloads, so
 *   {@code fire(key)} without a payload is still visible to resumed nodes.
 * - fire() marks each trigger as fired, so timeout watchers can skip the
 *   racy second resume.
 * <p>
 * Cross-process events (Kafka/RabbitMQ) are Stage 11 scope.
 */
public class EventBroker {

    private static final Logger log = LoggerFactory.getLogger(EventBroker.class);

    private final RunManager runManager;
    private final Map<String, List<EventTrigger>> subscriptions = new ConcurrentHashMap<>();
    private final Map<String, Object> eventPayloads = new ConcurrentHashMap<>();
    /** Keys that have fired, regardless of whether a payload was attached. */
    private final Set<String> firedKeys = ConcurrentHashMap.newKeySet();
    /** runId + eventKey -> trigger, kept after fire/timeout so nodes can inspect. */
    private final Map<String, EventTrigger> triggersByRunAndKey = new ConcurrentHashMap<>();

    public EventBroker(RunManager runManager) {
        this.runManager = runManager;
    }

    /**
     * Subscribe a run to an event. When the event fires, the run is resumed.
     */
    public EventTrigger subscribe(EventTrigger trigger) {
        subscriptions.computeIfAbsent(trigger.eventKey(), k -> new CopyOnWriteArrayList<>()).add(trigger);
        triggersByRunAndKey.put(indexKey(trigger.runId(), trigger.eventKey()), trigger);
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
     * to read; the fired state is recorded even when the payload is null.
     */
    public void fire(String eventKey, Object payload) {
        firedKeys.add(eventKey);
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
            if (!trigger.tryMarkFired()) {
                log.debug("[event] Fire '{}': trigger for run '{}' already claimed, skip resume",
                        eventKey, trigger.runId());
                continue;
            }
            try {
                runManager.resume(trigger.runId());
            } catch (Exception e) {
                log.error("[event] Failed to resume run '{}' for event '{}': {}",
                        trigger.runId(), eventKey, e.getMessage());
            }
        }
    }

    /**
     * Mark a wait as timed out and drop the subscription so a late fire
     * cannot resume a failed run.
     */
    public void timeout(EventTrigger trigger) {
        trigger.tryMarkTimedOut();
        List<EventTrigger> list = subscriptions.get(trigger.eventKey());
        if (list != null) {
            list.remove(trigger);
            if (list.isEmpty()) {
                subscriptions.remove(trigger.eventKey());
            }
        }
        log.warn("[event] Timed out run '{}' waiting for '{}'", trigger.runId(), trigger.eventKey());
    }

    public EventTrigger getTrigger(String runId, String eventKey) {
        return triggersByRunAndKey.get(indexKey(runId, eventKey));
    }

    public boolean isTimedOut(String runId, String eventKey) {
        EventTrigger trigger = getTrigger(runId, eventKey);
        return trigger != null && (trigger.wasTimedOut() || trigger.isTimedOut(java.time.Instant.now()));
    }

    private static String indexKey(String runId, String eventKey) {
        return runId + "\0" + eventKey;
    }

    /** Get the payload of a fired event (null if none was attached). */
    public Object getPayload(String eventKey) {
        return eventPayloads.get(eventKey);
    }

    /** Check if an event has fired (with or without payload). */
    public boolean hasFired(String eventKey) {
        return firedKeys.contains(eventKey);
    }

    /** List all pending event subscriptions (for debugging / inspection). */
    public Map<String, List<EventTrigger>> getSubscriptions() {
        return Map.copyOf(subscriptions);
    }
}

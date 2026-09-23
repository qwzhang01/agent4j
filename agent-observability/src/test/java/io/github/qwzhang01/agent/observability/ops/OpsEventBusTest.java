package io.github.qwzhang01.agent.observability.ops;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 7.4 acceptance for the ops event bus: one stream for budget,
 * sandbox, guardrail and A2A signals; every event carries coordinates and
 * a recommended action; broken subscribers never break publishers.
 */
class OpsEventBusTest {

    @Test
    void eventsCarryRecommendedActionsByContract() {
        assertThrows(IllegalArgumentException.class, () -> new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.GUARDRAIL_HIT, 2, Instant.now(), "run-1", null, null, null, null,
                "some hit", "  "), "blank action is rejected: an event without advice is just a number");
        assertThrows(IllegalArgumentException.class, () -> new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED, 4, Instant.now(), "run-1", null, null, null, null,
                "failed", "fix it"), "severity out of 1-3 rejected");
        assertThrows(IllegalArgumentException.class, () -> new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED, 2, Instant.now(), "run-1", null, null, null, null,
                " ", "fix it"), "blank message rejected");
    }

    @Test
    void publishDeliversToEverySubscriberInOrder() {
        OpsEventBus bus = new OpsEventBus();
        List<OpsEventBus.OpsEvent> received = new ArrayList<>();
        List<String> order = new ArrayList<>();
        bus.subscribe(e -> {
            received.add(e);
            order.add("first");
        });
        bus.subscribe(e -> order.add("second"));

        OpsEventBus.OpsEvent event = event(OpsEventBus.Kind.GUARDRAIL_HIT, "run-1", "hit");
        bus.publish(event);

        assertEquals(1, received.size());
        assertEquals(List.of("first", "second"), order);
        assertEquals(0, bus.deliveryFailures());
    }

    @Test
    void brokenSubscriberIsCountedNotPropagated() {
        OpsEventBus bus = new OpsEventBus();
        List<OpsEventBus.OpsEvent> healthy = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("subscriber exploded");
        });
        bus.subscribe(healthy::add);

        bus.publish(event(OpsEventBus.Kind.RUN_FAILED, "run-9", "failed"));
        bus.publish(event(OpsEventBus.Kind.BUDGET_EXHAUSTED, "run-9", "gone"));

        assertEquals(2, healthy.size(), "the healthy subscriber still got everything");
        assertEquals(2, bus.deliveryFailures(), "each broken delivery counted honestly");
    }

    @Test
    void subscribeIsIdempotentAndUnsubscribeSafe() {
        OpsEventBus bus = new OpsEventBus();
        List<OpsEventBus.OpsEvent> received = new ArrayList<>();
        OpsEventBus.Subscriber subscriber = received::add;

        bus.subscribe(subscriber);
        bus.subscribe(subscriber);
        assertEquals(1, bus.subscriberCount(), "same instance subscribed once");

        bus.unsubscribe(subscriber);
        bus.unsubscribe(subscriber);
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    void severityThreeIsPageWorthy() {
        OpsEventBus.OpsEvent page = event(OpsEventBus.Kind.BUDGET_EXHAUSTED, "run-1", "gone");
        assertTrue(page.pageWorthy());
        assertEquals(3, page.severity());
    }

    private static OpsEventBus.OpsEvent event(OpsEventBus.Kind kind, String runId, String message) {
        return new OpsEventBus.OpsEvent(
                kind, 3, Instant.now(), runId, "step-1", "search", "gpt-4o",
                "PROMPT v3, MODEL premium", message, "do the recommended thing");
    }
}

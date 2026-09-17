package io.github.qwzhang01.agent.observability.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Harness 4.4 (2026-09-17): consumer-side dedup for the fire-and-forget
 * ops bus. At-least-once recovery replays land the same logical event
 * twice; the deduplicator collapses them per structural key within a
 * bounded window, and its counters tell the operator how much
 * re-delivery upstream actually does.
 */
class OpsEventDeduplicatorTest {

    private static OpsEventBus.OpsEvent event(int severity, String runId, String message) {
        return new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED, severity, Instant.now(), runId,
                null, null, null, null, message, "check run health");
    }

    @Test
    @DisplayName("same structural key within the window: delivered once, re-delivery suppressed")
    void duplicateWithinWindowIsSuppressed() {
        List<OpsEventBus.OpsEvent> seen = new ArrayList<>();
        OpsEventDeduplicator dedup = new OpsEventDeduplicator(seen::add, Duration.ofMinutes(5), 100);

        OpsEventBus.OpsEvent first = event(3, "r1", "model provider down");
        dedup.accept(first);
        dedup.accept(first);
        dedup.accept(first);

        assertEquals(1, seen.size(), "logical event delivered exactly once");
        assertEquals(2, dedup.duplicatesSuppressed());
        assertEquals(1, dedup.delivered());
    }

    @Test
    @DisplayName("different severity on the same coordinates is a different fact (escalation)")
    void severityChangeIsNotADuplicate() {
        List<OpsEventBus.OpsEvent> seen = new ArrayList<>();
        OpsEventDeduplicator dedup = new OpsEventDeduplicator(seen::add, Duration.ofMinutes(5), 100);

        dedup.accept(event(2, "r1", "flaky"));
        dedup.accept(event(3, "r1", "flaky escalated"));

        assertEquals(2, seen.size(), "escalation is a new fact, never folded into the prior one");
        assertEquals(0, dedup.duplicatesSuppressed());
    }

    @Test
    @DisplayName("window expiry: the same key after the window is a legitimate re-delivery")
    void windowExpiryLetsTheSameKeyThrough() {
        List<OpsEventBus.OpsEvent> seen = new ArrayList<>();
        OpsEventDeduplicator dedup = new OpsEventDeduplicator(seen::add, Duration.ofSeconds(1), 100);

        Instant t0 = Instant.now();
        OpsEventBus.OpsEvent early = new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED, 3, t0, "r1", null, null, null, null,
                "down", "check run health");
        OpsEventBus.OpsEvent late = new OpsEventBus.OpsEvent(
                OpsEventBus.Kind.RUN_FAILED, 3, t0.plusSeconds(10), "r1", null, null, null, null,
                "down again", "check run health");
        dedup.accept(early);
        dedup.accept(late);

        assertEquals(2, seen.size(), "outside the window the same coordinates are a fresh fact");
        assertEquals(1, dedup.duplicatesSuppressed() == 0 ? 1 : 0, "sanity: nothing suppressed");
    }

    @Test
    @DisplayName("capacity eviction is fail-open: an evicted key re-delivers, a real event is never dropped")
    void capacityEvictionFailsOpen() {
        List<OpsEventBus.OpsEvent> seen = new ArrayList<>();
        OpsEventDeduplicator dedup = new OpsEventDeduplicator(seen::add, Duration.ofMinutes(5), 2);

        // Fill to capacity, then push one more: the LRU evicts the oldest.
        dedup.accept(event(3, "r1", "one"));
        dedup.accept(event(3, "r2", "two"));
        dedup.accept(event(3, "r3", "three")); // evicts r1

        // r1's key was evicted: its re-delivery passes through again.
        dedup.accept(event(3, "r1", "one re-delivered"));

        assertEquals(4, seen.size(), "evicted duplicate re-delivered (fail-open), nothing dropped");
        assertEquals(0, dedup.duplicatesSuppressed());
    }

    @Test
    @DisplayName("throwing downstream propagates: this is the subscriber's pipeline, not the bus's side channel")
    void throwingDownstreamPropagates() {
        OpsEventDeduplicator dedup = new OpsEventDeduplicator(
                e -> { throw new IllegalStateException("downstream broken"); },
                Duration.ofMinutes(5), 100);

        assertThrows(IllegalStateException.class, () -> dedup.accept(event(3, "r1", "boom")));
        assertEquals(1, dedup.delivered(), "counted as delivered before the downstream threw");
    }

    @Test
    @DisplayName("capacity must be positive: fail-loud wiring mistakes")
    void nonPositiveCapacityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpsEventDeduplicator(e -> { }, Duration.ofMinutes(5), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OpsEventDeduplicator(e -> { }, Duration.ofMinutes(5), -1));
    }
}

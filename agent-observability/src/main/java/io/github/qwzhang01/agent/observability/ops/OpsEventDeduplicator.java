package io.github.qwzhang01.agent.observability.ops;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Consumer-side deduplication for {@link OpsEventBus} subscribers
 * (harness 4.4, roadmap 3.4: "event recovery is idempotent with
 * duplicate-message dedup").
 * <p>
 * The premise: the bus is fire-and-forget and delivery guarantees are the
 * subscriber's problem — at-least-once recovery replays (sweep re-emission,
 * restart re-delivery) land the SAME logical event more than once. The
 * deduplicator collapses those into one delivery per structural key within
 * a bounded window.
 * <p>
 * The key is STRUCTURAL (kind + run/step/tool/provider/version
 * coordinates), never the message text: two different messages about the
 * same coordinates are different facts (a message can be rewritten between
 * emissions), but the same coordinate pair within the window is the same
 * fact re-delivered. Severity participates too: the same coordinates at a
 * different severity is a DIFFERENT fact (escalation), not a duplicate.
 * <p>
 * Windowing: a fixed dedup window (default 5 minutes). Entries age out on
 * access; the map is capacity-bounded (LRU) so a pathological flood cannot
 * grow it unboundedly — beyond capacity the OLDEST entries are evicted,
 * which means an evicted duplicate would be re-delivered (fail-open), never
 * a legit new event dropped. Dropping real facts to save memory is the
 * worse failure mode.
 * <p>
 * Counters are honest: {@code delivered}/{@code duplicatesSuppressed} tell
 * the operator exactly how much re-delivery the upstream actually does.
 */
public final class OpsEventDeduplicator implements Consumer<OpsEventBus.OpsEvent> {

    /** Default dedup window: 5 minutes. */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(5);

    /** Default capacity: enough entries for a busy multi-tenant sweep. */
    public static final int DEFAULT_CAPACITY = 10_000;

    private final Consumer<OpsEventBus.OpsEvent> downstream;
    private final Duration window;
    private final LinkedHashMap<String, Instant> seen;

    private long delivered;
    private long duplicatesSuppressed;

    /**
     * @param downstream the real subscriber that must see each logical
     *                   event exactly once per window
     * @param window     how long a delivered key stays remembered
     * @param capacity   max remembered keys (LRU eviction of the oldest)
     */
    public OpsEventDeduplicator(Consumer<OpsEventBus.OpsEvent> downstream,
                                Duration window, int capacity) {
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.window = Objects.requireNonNull(window, "window");
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.seen = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
                return size() > capacity;
            }
        };
    }

    /** Defaults: 5-minute window, 10k capacity. */
    public OpsEventDeduplicator(Consumer<OpsEventBus.OpsEvent> downstream) {
        this(downstream, DEFAULT_WINDOW, DEFAULT_CAPACITY);
    }

    /**
     * Receive one event: deliver downstream on first sight of its key,
     * suppress when the same key was already delivered inside the window.
     * A throwing downstream propagates (this is the subscriber's own
     * pipeline, not the bus's side channel).
     */
    @Override
    public synchronized void accept(OpsEventBus.OpsEvent event) {
        Objects.requireNonNull(event, "event");
        String key = keyOf(event);
        Instant now = event.occurredAt() != null ? event.occurredAt() : Instant.now();

        Instant prior = seen.get(key);
        if (prior != null && now.minus(window).isBefore(prior)) {
            duplicatesSuppressed++;
            return;
        }
        seen.put(key, now);
        delivered++;
        downstream.accept(event);
    }

    /** The structural identity of an event for dedup purposes. */
    static String keyOf(OpsEventBus.OpsEvent e) {
        return e.kind() + "|" + e.severity() + "|" + nvl(e.runId()) + "|"
                + nvl(e.stepId()) + "|" + nvl(e.toolName()) + "|"
                + nvl(e.providerName()) + "|" + nvl(e.versionCombination());
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    /** Events passed through to downstream (new keys). */
    public synchronized long delivered() {
        return delivered;
    }

    /** Re-deliveries collapsed (same key within the window). */
    public synchronized long duplicatesSuppressed() {
        return duplicatesSuppressed;
    }
}

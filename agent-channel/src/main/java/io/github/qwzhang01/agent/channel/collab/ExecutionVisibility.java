package io.github.qwzhang01.agent.channel.collab;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Publish-subscribe stream of {@link VisibilityEvent}s (Stage 12 M12.3,
 * design D6).
 * <p>
 * Push beats poll: members subscribe once and receive every milestone in
 * order. The {@link TaskBoard} subscribes like any other listener - the
 * board is a materialized view of this stream, not a second source of
 * truth.
 * <p>
 * Listener isolation: a throwing listener is logged and skipped; one bad
 * subscriber must not break the loop for others (same discipline as
 * IdentityResolver's audit sink).
 */
public class ExecutionVisibility {

    private static final Logger log = LoggerFactory.getLogger(ExecutionVisibility.class);

    /** Receives visibility events as they happen. */
    @FunctionalInterface
    public interface Listener {
        void onEvent(VisibilityEvent event);
    }

    private final String channelId;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public ExecutionVisibility(String channelId) {
        this.channelId = Objects.requireNonNull(channelId, "channelId must not be null");
    }

    /**
     * Subscribe to the stream. Events already emitted are NOT replayed
     * (the TaskBoard holds the materialized view for late joiners).
     */
    public ExecutionVisibility subscribe(Listener listener) {
        Objects.requireNonNull(listener, "listener must not be null");
        listeners.add(listener);
        return this;
    }

    /**
     * Publish an event to all subscribers, in order.
     * Mainly used by {@code SharedAgentSession}; exposed for tests and
     * assembly-layer integrations.
     */
    public void publish(VisibilityEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        log.debug("[visibility] {} task={} actor={} detail={}",
                event.type(), event.taskId(), event.actor(), event.detail());
        for (Listener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (Exception e) {
                log.error("[visibility] Listener threw on {}: ", event.type(), e);
            }
        }
    }

    /**
     * The channel this stream belongs to.
     */
    public String channelId() {
        return channelId;
    }
}

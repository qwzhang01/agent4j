package io.github.qwzhang01.agent.observability.ops;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One event bus for operations (Stage 7.4): budget exhaustion, sandbox
 * escalations, guardrail hits and A2A refusals land on the SAME bus - not
 * four scattered logs an on-call engineer has to grep by hand.
 * <p>
 * Event taxonomy is closed ({@link Kind}): the four cross-cutting signals
 * the roadmap names, plus RUN failure as the anchor every investigation
 * starts from. Each event carries WHERE it happened (run/step/tool/provider
 * coordinates - the 7.4 "locate to Run/Step/Tool/Provider/version" half)
 * and WHAT to do about it (recommendedAction - the "advice, not numbers"
 * half, applied here first).
 * <p>
 * Bus discipline: subscribe/unsubscribe at assembly time; a broken
 * subscriber never breaks the publisher (side-channel rule, swallowed and
 * counted); events are immutable records.
 */
public final class OpsEventBus {

    private static final Logger log = LoggerFactory.getLogger(OpsEventBus.class);

    private final Set<Subscriber> subscribers = new CopyOnWriteArraySet<>();
    private long deliveryFailures;

    /** Consumer of ops events. */
    @FunctionalInterface
    public interface Subscriber {

        void onEvent(OpsEvent event);
    }

    /** The closed taxonomy of operations events. */
    public enum Kind {
        BUDGET_EXHAUSTED,
        SANDBOX_ESCALATION,
        GUARDRAIL_HIT,
        A2A_REFUSED,
        RUN_FAILED
    }

    /**
     * One operations event.
     *
     * @param kind              which signal
     * @param severity          1 = info line, 2 = attention, 3 = page-worthy
     * @param occurredAt        wall clock
     * @param runId             the run it happened in (null only for
     *                          assembly-level events like a tenant budget)
     * @param stepId            the step, when known
     * @param toolName          the tool, when the event is tool-scoped
     * @param providerName      the provider/model, when the event is
     *                          model-scoped
     * @param versionCombination which prompt/model/tool combination was
     *                          serving (RunRecord.combination(), null unknown)
     * @param message           one-line description
     * @param recommendedAction what the on-call should do, never blank
     */
    public record OpsEvent(
            Kind kind,
            int severity,
            Instant occurredAt,
            String runId,
            String stepId,
            String toolName,
            String providerName,
            String versionCombination,
            String message,
            String recommendedAction) {

        public OpsEvent {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(occurredAt, "occurredAt");
            if (severity < 1 || severity > 3) {
                throw new IllegalArgumentException("severity must be within 1-3: " + severity);
            }
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be null or blank");
            }
            if (recommendedAction == null || recommendedAction.isBlank()) {
                throw new IllegalArgumentException(
                        "an event without a recommended action is just a number (7.4 discipline)");
            }
        }

        /** Page-worthy? (severity 3) */
        public boolean pageWorthy() {
            return severity == 3;
        }
    }

    public void subscribe(Subscriber subscriber) {
        Objects.requireNonNull(subscriber, "subscriber");
        subscribers.add(subscriber);
    }

    /** Unsubscribe (no-op when absent). */
    public void unsubscribe(Subscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /** Publish to every subscriber; broken subscribers are counted, never propagated. */
    public void publish(OpsEvent event) {
        Objects.requireNonNull(event, "event");
        for (Subscriber subscriber : subscribers) {
            try {
                subscriber.onEvent(event);
            } catch (RuntimeException e) {
                deliveryFailures++;
                log.warn("ops subscriber failed (side channel, swallowed): {}", e.toString());
            }
        }
    }

    /** Failed deliveries (the honest drop ledger). */
    public long deliveryFailures() {
        return deliveryFailures;
    }

    /** Subscriber count (wiring check for tests and dashboards). */
    public int subscriberCount() {
        return subscribers.size();
    }
}

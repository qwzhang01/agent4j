package io.github.qwzhang01.agent.channel.ambient;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A standing instruction: what the channel agent should keep watching,
 * when to check, and what is worth saying (Stage 12 M12.4, design D3).
 * <p>
 * An instruction is AGENT semantics, not a dumb cron script: it carries a
 * condition (is it worth bothering anyone?), a message producer (what to
 * say), and an importance level (how loudly). Compare with cron: cron has
 * a schedule and a command; an AmbientInstruction has a schedule-or-event,
 * a judgment, and a voice.
 * <p>
 * v1 honest boundary: condition and message are Java functions supplied
 * by the assembly layer. Natural-language standing instructions ("help me
 * keep an eye on X") parsed by the LLM are Stage 13 declarative-layer
 * scope.
 *
 * @param instructionId unique id
 * @param description   human-readable instruction text
 * @param trigger       SCHEDULED(interval) or EVENT(eventKey)
 * @param importance    noise tier: INFO (digest), WARN / CRITICAL (realtime)
 * @param condition     payload -> is it worth a notification at all
 *                      (false = total silence, not even digest)
 * @param message       payload -> what to say
 */
public record AmbientInstruction(
        String instructionId,
        String description,
        Trigger trigger,
        Importance importance,
        Predicate<Object> condition,
        Function<Object, String> message
) {

    /** Noise tiers (D7 gate 4): INFO digests, WARN+ pushes in realtime. */
    public enum Importance {
        INFO, WARN, CRITICAL
    }

    /** When to check: on a schedule, or when an event fires. */
    public sealed interface Trigger permits Scheduled, OnEvent {
    }

    /** Check every interval (e.g. every 10 minutes). */
    public record Scheduled(Duration interval) implements Trigger {
        public Scheduled {
            Objects.requireNonNull(interval, "interval must not be null");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("interval must be positive: " + interval);
            }
        }
    }

    /** Check when an external system fires the given event key. */
    public record OnEvent(String eventKey) implements Trigger {
        public OnEvent {
            Objects.requireNonNull(eventKey, "eventKey must not be null");
            if (eventKey.isBlank()) {
                throw new IllegalArgumentException("eventKey must not be blank");
            }
        }
    }

    public AmbientInstruction {
        Objects.requireNonNull(instructionId, "instructionId must not be null");
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(importance, "importance must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    // ============ Factory Methods ============

    /**
     * A scheduled standing instruction (check every interval).
     */
    public static AmbientInstruction scheduled(String id, String description, Duration interval,
                                               Importance importance,
                                               Predicate<Object> condition,
                                               Function<Object, String> message) {
        return new AmbientInstruction(id, description, new Scheduled(interval),
                importance, condition, message);
    }

    /**
     * An event-driven standing instruction (check when eventKey fires).
     */
    public static AmbientInstruction onEvent(String id, String description, String eventKey,
                                              Importance importance,
                                              Predicate<Object> condition,
                                              Function<Object, String> message) {
        return new AmbientInstruction(id, description, new OnEvent(eventKey),
                importance, condition, message);
    }
}

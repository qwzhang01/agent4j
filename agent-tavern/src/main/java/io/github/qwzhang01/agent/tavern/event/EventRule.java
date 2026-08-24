package io.github.qwzhang01.agent.tavern.event;

import io.github.qwzhang01.agent.tavern.world.WorldEffect;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One trigger rule: a condition over game facts, the event it fires, and the
 * world effects that come along (Stage 16 M16.3, blueprint D5).
 * <p>
 * {@code once = true} (the default) is the story-semantics default: a plot
 * event happens once ever; repeatable conditions use {@link #repeatable}.
 */
public record EventRule(
        String ruleId,
        Predicate<GameFacts> condition,
        GameEvent event,
        List<WorldEffect> effects,
        boolean once
) {

    public EventRule {
        if (ruleId == null || ruleId.isBlank()) {
            throw new IllegalArgumentException("ruleId must not be null or blank");
        }
        Objects.requireNonNull(condition, "condition must not be null");
        Objects.requireNonNull(event, "event must not be null");
        effects = effects == null ? List.of() : List.copyOf(effects);
    }

    /** A once-only rule: fires at most once per game. */
    public static EventRule once(String ruleId, Predicate<GameFacts> condition,
                                 GameEvent event, WorldEffect... effects) {
        return new EventRule(ruleId, condition, event, List.of(effects), true);
    }

    /** A repeatable rule: may fire on every settlement where it matches. */
    public static EventRule repeatable(String ruleId, Predicate<GameFacts> condition,
                                       GameEvent event, WorldEffect... effects) {
        return new EventRule(ruleId, condition, event, List.of(effects), false);
    }
}

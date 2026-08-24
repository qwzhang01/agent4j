package io.github.qwzhang01.agent.tavern.event;

import io.github.qwzhang01.agent.tavern.world.WorldEffect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Synchronous rule evaluation at the turn settlement point (Stage 16 M16.3,
 * blueprint D5: events are a settlement judgment, NOT an EventBroker).
 * <p>
 * Two entry paths over the same rule table:
 * <ul>
 *   <li>{@link #evaluate(GameFacts)} - the automatic path, called once per
 *       settlement (exactly one pass: effects applied because of this batch
 *       never re-trigger evaluation in the same turn - the anti-storm
 *       guarantee lives in the ENGINE calling this exactly once);</li>
 *   <li>{@link #triggerManually(String)} - the manual path used by the
 *       {@code trigger_event} tool: no condition check (deliberate dramatic
 *       authorization), but the same once bookkeeping.</li>
 * </ul>
 * A condition that throws is treated as not-matching (fail-soft): one broken
 * rule must not kill the whole settlement - the same semantics as Stage 12's
 * ambient conditions.
 * <p>
 * The fired set is engine-observable for the M16.4 save file.
 */
public final class EventEvaluator {

    private final List<EventRule> rules;
    private final Set<String> firedEventIds = new HashSet<>();

    public EventEvaluator(List<EventRule> rules) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * One evaluation pass over all rules, in rule order.
     */
    public List<TriggeredEvent> evaluate(GameFacts facts) {
        List<TriggeredEvent> fired = new ArrayList<>();
        for (EventRule rule : rules) {
            if (rule.once() && firedEventIds.contains(rule.event().eventId())) {
                continue;
            }
            boolean matches;
            try {
                matches = rule.condition().test(facts);
            } catch (RuntimeException e) {
                matches = false;
            }
            if (matches) {
                firedEventIds.add(rule.event().eventId());
                fired.add(new TriggeredEvent(rule.event(), rule.effects()));
            }
        }
        return fired;
    }

    /**
     * Manual trigger by eventId: no condition check (the caller's dramatic
     * intent is the authorization), once bookkeeping still applies.
     *
     * @return the triggered event with its effects; empty if the id is
     *         unknown or the event already fired (once rules)
     */
    public Optional<TriggeredEvent> triggerManually(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return Optional.empty();
        }
        for (EventRule rule : rules) {
            if (rule.event().eventId().equals(eventId)) {
                if (rule.once() && firedEventIds.contains(eventId)) {
                    return Optional.empty();
                }
                firedEventIds.add(eventId);
                return Optional.of(new TriggeredEvent(rule.event(), rule.effects()));
            }
        }
        return Optional.empty();
    }

    /** Event ids that already fired (once bookkeeping; save-file view). */
    public Set<String> firedEventIds() {
        return Set.copyOf(firedEventIds);
    }

    /**
     * Restore the fired set from a save (M16.4 load path): a once-event that
     * already happened stays happened after a reload.
     */
    public void restore(Set<String> fired) {
        this.firedEventIds.clear();
        if (fired != null) {
            this.firedEventIds.addAll(fired);
        }
    }

    /** All rule ids (inspection). */
    public List<String> ruleIds() {
        return rules.stream().map(EventRule::ruleId).toList();
    }

    /**
     * A fired event together with its carried effects - the unit the engine
     * processes at settlement.
     */
    public record TriggeredEvent(GameEvent event, List<WorldEffect> effects) {
        public TriggeredEvent {
            if (event == null) {
                throw new IllegalArgumentException("event must not be null");
            }
            effects = effects == null ? List.of() : List.copyOf(effects);
        }
    }
}

package io.github.qwzhang01.agent.tavern.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.3: the synchronous rule evaluator - once semantics, fail-soft
 * conditions, and the manual-trigger catalog (blueprint D5).
 */
class EventEvaluatorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GameFacts factsWithFlag(String key) {
        WorldState world = WorldState.initial("hall").apply(new WorldEffect.SetFlag(key, "yes"));
        return new GameFacts(world, java.util.Map.of("marcus", new Relationship(80, 1)), 8);
    }

    @Test
    @DisplayName("a matching rule fires once and is remembered")
    void matchingRuleFires() {
        GameEvent toast = new GameEvent("toast", "The whole tavern raises a glass.", "marcus");
        EventEvaluator evaluator = new EventEvaluator(List.of(
                EventRule.once("r1", f -> f.world().flag("cheer").isPresent(), toast)));

        List<EventEvaluator.TriggeredEvent> fired = evaluator.evaluate(factsWithFlag("cheer"));

        assertEquals(1, fired.size());
        assertEquals("toast", fired.get(0).event().eventId());
        assertTrue(evaluator.firedEventIds().contains("toast"));
    }

    @Test
    @DisplayName("a non-matching rule does not fire")
    void nonMatchingRuleDoesNotFire() {
        EventEvaluator evaluator = new EventEvaluator(List.of(
                EventRule.once("r1", f -> f.world().flag("cheer").isPresent(),
                        new GameEvent("toast", "cheers", null))));

        assertTrue(evaluator.evaluate(factsWithFlag("silence")).isEmpty());
    }

    @Test
    @DisplayName("once rules fire at most once across evaluations")
    void onceSemantics() {
        EventRule rule = EventRule.once("r1",
                f -> f.relationship("marcus").value() >= 80,
                new GameEvent("confession", "The barkeep confesses.", "marcus"));
        EventEvaluator evaluator = new EventEvaluator(List.of(rule));

        assertEquals(1, evaluator.evaluate(factsWithFlag("x")).size());
        assertTrue(evaluator.evaluate(factsWithFlag("x")).isEmpty(),
                "second evaluation: condition still true, but the event already fired");
    }

    @Test
    @DisplayName("repeatable rules can fire on every matching settlement")
    void repeatableSemantics() {
        EventRule rule = EventRule.repeatable("r1",
                f -> f.turnNo() >= 2,
                new GameEvent("clock", "The old clock strikes.", null));
        EventEvaluator evaluator = new EventEvaluator(List.of(rule));

        assertEquals(1, evaluator.evaluate(factsWithFlag("x")).size());
        assertEquals(1, evaluator.evaluate(factsWithFlag("x")).size());
    }

    @Test
    @DisplayName("a throwing condition is treated as not-matching (fail-soft)")
    void throwingConditionIsFailSoft() {
        EventRule broken = EventRule.once("r1",
                f -> { throw new IllegalStateException("bad rule"); },
                new GameEvent("boom", "never fires", null));
        EventRule healthy = EventRule.once("r2",
                f -> true, new GameEvent("fine", "always fires", null));
        EventEvaluator evaluator = new EventEvaluator(List.of(broken, healthy));

        List<EventEvaluator.TriggeredEvent> fired = evaluator.evaluate(factsWithFlag("x"));

        assertEquals(1, fired.size());
        assertEquals("fine", fired.get(0).event().eventId(),
                "one broken rule must not kill the settlement");
    }

    @Test
    @DisplayName("facts expose relationships with a neutral default")
    void factsRelationshipDefault() {
        GameFacts facts = factsWithFlag("x");

        assertEquals(80, facts.relationship("marcus").value());
        assertEquals(50, facts.relationship("unknown").value());
        assertEquals(8, facts.turnNo());
    }

    // ============ Manual Trigger ============

    @Test
    @DisplayName("manual trigger bypasses the condition but not the once bookkeeping")
    void manualTriggerSemantics() {
        // condition would NEVER match on its own
        EventRule rule = EventRule.once("r1",
                f -> false,
                new GameEvent("cheers", "The crowd erupts.", "lyra"),
                new WorldEffect.SetFlag("crowd", "cheering"));
        EventEvaluator evaluator = new EventEvaluator(List.of(rule));

        Optional<EventEvaluator.TriggeredEvent> first = evaluator.triggerManually("cheers");
        assertTrue(first.isPresent(), "manual trigger ignores the condition");
        assertEquals(1, first.get().effects().size());

        Optional<EventEvaluator.TriggeredEvent> second = evaluator.triggerManually("cheers");
        assertTrue(second.isEmpty(), "once bookkeeping still applies to manual triggers");

        // and the automatic path will not fire it anymore either
        assertTrue(evaluator.evaluate(factsWithFlag("x")).isEmpty());
    }

    @Test
    @DisplayName("manual trigger of an unknown id is empty")
    void manualTriggerUnknown() {
        EventEvaluator evaluator = new EventEvaluator(List.of());

        assertTrue(evaluator.triggerManually("nope").isEmpty());
        assertTrue(evaluator.triggerManually(null).isEmpty());
    }

    // ============ TriggerEventTool ============

    @Test
    @DisplayName("the trigger_event tool queues to the sink and reports the delay")
    void toolQueuesAndReports() throws Exception {
        EventRule rule = EventRule.once("r1", f -> false,
                new GameEvent("cheers", "The crowd erupts.", "lyra"));
        EventEvaluator evaluator = new EventEvaluator(List.of(rule));
        List<EventEvaluator.TriggeredEvent> queue = new java.util.ArrayList<>();
        TriggerEventTool tool = new TriggerEventTool(evaluator, queue::add);

        String result = tool.execute(mapper.readTree("{\"eventId\":\"cheers\"}"));

        assertTrue(result.contains("cheers"));
        assertTrue(result.contains("end of this turn"), "consequences unfold at settlement");
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("the trigger_event tool reports unknown/already-fired ids as [REJECTED] text")
    void toolRejectsUnknownOrSpent() throws Exception {
        EventRule rule = EventRule.once("r1", f -> false,
                new GameEvent("cheers", "The crowd erupts.", null));
        EventEvaluator evaluator = new EventEvaluator(List.of(rule));
        TriggerEventTool tool = new TriggerEventTool(evaluator, t -> { });

        assertTrue(tool.execute(mapper.readTree("{\"eventId\":\"ghost\"}"))
                .startsWith("[REJECTED]"));

        evaluator.triggerManually("cheers");
        assertTrue(tool.execute(mapper.readTree("{\"eventId\":\"cheers\"}"))
                .startsWith("[REJECTED]"), "already fired once");
    }

    @Test
    @DisplayName("missing eventId is a ToolException")
    void toolMissingId() {
        TriggerEventTool tool = new TriggerEventTool(new EventEvaluator(List.of()), t -> { });
        assertThrows(ToolException.class, () -> tool.execute(mapper.createObjectNode()));
    }

    @Test
    @DisplayName("GameEvent and EventRule validate their invariants fail-fast")
    void recordGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new GameEvent(" ", "desc", null));
        assertThrows(IllegalArgumentException.class,
                () -> new GameEvent("id", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new EventRule(" ", f -> true, new GameEvent("e", "d", null), null, true));
    }
}

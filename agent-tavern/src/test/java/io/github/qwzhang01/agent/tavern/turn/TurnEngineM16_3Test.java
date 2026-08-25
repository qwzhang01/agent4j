package io.github.qwzhang01.agent.tavern.turn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.core.tool.DefaultToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolExecutor;
import io.github.qwzhang01.agent.core.tool.ToolRegistry;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.GovernedToolExecutor;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.security.PermissionChecker;
import io.github.qwzhang01.agent.security.ToolPermission;
import io.github.qwzhang01.agent.security.ToolPolicy;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventEvaluator;
import io.github.qwzhang01.agent.tavern.event.EventRule;
import io.github.qwzhang01.agent.tavern.event.GameEvent;
import io.github.qwzhang01.agent.tavern.relation.RelationshipMatrix;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.3 integration: relationships + events through the full turn
 * pipeline - blueprint acceptance under test.
 * <p>
 * Covers: relationship tool changing the matrix and landing in the turn
 * record; [relationship] notes derived from the matrix; limit rejection read
 * by the model as a failure observation (self-correction); rule-triggered
 * events with event-driven responses; exactly-one-pass settlement (no
 * cascade); manual trigger via the tool, deferred to settlement.
 */
class TurnEngineM16_3Test {

    private static final class CapturingModelClient implements ModelClient {

        private final List<ModelRequest> requests = new ArrayList<>();
        private final Queue<ModelResponse> responses = new LinkedBlockingQueue<>();

        CapturingModelClient(ModelResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        List<ModelRequest> requests() {
            return requests;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            requests.add(request);
            if (responses.isEmpty()) {
                throw new IllegalStateException("no more scripted responses");
            }
            return responses.poll();
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            return Stream.of(new StreamEvent.Done(chat(request)));
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    private InMemoryMemoryStore store;
    private CharacterCard marcus;
    private CharacterCard lyra;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        marcus = new CharacterCard("marcus", "Marcus", "warm-hearted barkeep", null);
        lyra = new CharacterCard("lyra", "Lyra", "sharp-tongued bard", null);
    }

    private ObjectNode args(String json) {
        try {
            return (ObjectNode) mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ============ Relationship Tools ============

    @Test
    @DisplayName("adjust_relationship changes the matrix and lands in the turn record")
    void relationshipToolFullChain() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "adjust_relationship",
                                args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("You seem decent, traveler."));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus A mug of mead, and keep one for yourself."));

        assertEquals(53, engine.relationships().view("marcus").value());
        assertEquals(1, turn.turn().relationshipChanges().size());
        Turn.RelationshipChange change = turn.turn().relationshipChanges().get(0);
        assertEquals("marcus", change.characterId());
        assertEquals(3, change.delta());
        assertEquals(50, change.before());
        assertEquals(53, change.after());
    }

    @Test
    @DisplayName("[relationship] notes are derived from the matrix the model sees")
    void relationshipNoteDerivedFromMatrix() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Hello."));
        // wide policy so the pre-game warm-up can jump in one apply
        RelationshipMatrix matrix = new RelationshipMatrix(
                new io.github.qwzhang01.agent.tavern.relation.RelationshipPolicy(20));
        matrix.apply("marcus", 12, 0);   // pre-game warm-up to 62 (WARM)
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus), "game-1", WorldState.initial("hall"), matrix, null);

        engine.playTurn("@marcus Evening!");

        String injected = model.requests().get(0).messages().get(1).content();
        assertTrue(injected.contains("[relationship] affection 62 (WARM)"),
                "matrix-derived note, actual: " + injected);
    }

    @Test
    @DisplayName("an oversized adjustment is rejected, read by the model, self-corrected")
    void limitRejectionSelfCorrects() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "adjust_relationship",
                                args("{\"characterId\":\"marcus\",\"delta\":10}")))),
                ModelResponse.text("Whoa, let's not get ahead of ourselves. What'll it be?"));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus You're the best barkeep in the realm!"));

        // world unchanged, nothing recorded
        assertEquals(50, engine.relationships().view("marcus").value());
        assertTrue(turn.turn().relationshipChanges().isEmpty());

        // the model READ the failure: the second request contains the rejection
        String secondRequest = model.requests().get(1).messages().stream()
                .map(ChatMessage::content)
                .filter(c -> c != null && c.contains("[REJECTED]"))
                .findFirst().orElse("");
        assertTrue(secondRequest.contains("±5"),
                "the model sees the failure observation, actual: " + secondRequest);

        // and still finished the scene
        assertEquals("Whoa, let's not get ahead of ourselves. What'll it be?",
                turn.turn().responses().get(0).text());
    }

    // ============ Event Settlement ============

    @Test
    @DisplayName("a rule fires at settlement: effects apply and the respondCharacter answers")
    void ruleFiresAtSettlement() {
        // rule: when the bard-mood flag is lively at turn >= 1, the bard improvises
        EventRule rule = EventRule.once("bard-improv",
                f -> f.world().flag("bard-mood").map(v -> v.equals("lively")).orElse(false),
                new GameEvent("improvisation", "Lyra strikes up an unannounced improvisation.", "lyra"),
                new WorldEffect.SetFlag("crowd", "cheering"));
        CapturingModelClient model = new CapturingModelClient(
                // marcus's reply, includes setting the flag
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag",
                                args("{\"key\":\"bard-mood\",\"value\":\"lively\"}")))),
                ModelResponse.text("Lyra's in fine form tonight."),
                // lyra's event-driven response
                ModelResponse.text("A tune demands an audience!"));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                null, new EventEvaluator(List.of(rule)));

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus The bard sounds great tonight."));

        // event effects applied (tool flag + event-carried flag)
        assertTrue(engine.world().flag("crowd").isPresent(), "event-carried effect applied");
        // event-driven response appended, after the speaking character's
        assertEquals(2, turn.turn().responses().size());
        assertEquals("marcus", turn.turn().responses().get(0).characterId());
        Turn.CharacterResponse eventResponse = turn.turn().responses().get(1);
        assertEquals("lyra", eventResponse.characterId());
        assertTrue(eventResponse.eventDriven());
        assertEquals("A tune demands an audience!", eventResponse.text());
        // and the event is recorded on the turn
        assertEquals(List.of("improvisation"), turn.turn().triggeredEventIds());
    }

    @Test
    @DisplayName("no cascade: an effect that would enable another rule does not re-evaluate")
    void noCascadeWithinTurn() {
        // rule A sets flag-b; rule B's condition depends on flag-b
        EventRule ruleA = EventRule.once("a",
                f -> f.world().flag("flag-a").isPresent(),
                new GameEvent("event-a", "A happens.", null),
                new WorldEffect.SetFlag("flag-b", "yes"));
        EventRule ruleB = EventRule.once("b",
                f -> f.world().flag("flag-b").isPresent(),
                new GameEvent("event-b", "B happens.", null));
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag",
                                args("{\"key\":\"flag-a\",\"value\":\"yes\"}")))),
                ModelResponse.text("done."),
                ModelResponse.text("second turn"));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus), "game-1", WorldState.initial("hall"),
                null, new EventEvaluator(List.of(ruleA, ruleB)));

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus go"));

        assertEquals(List.of("event-a"), turn.turn().triggeredEventIds(),
                "B's condition became true mid-settlement but evaluation is exactly one pass");
        assertTrue(engine.world().flag("flag-b").isPresent(), "A's effect WAS applied");

        // next turn: B fires (its condition holds, one-pass per turn)
        TurnResult.Completed second = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus more"));
        assertEquals(List.of("event-b"), second.turn().triggeredEventIds());
    }

    @Test
    @DisplayName("manual trigger via the tool unfolds at the same turn's settlement")
    void manualTriggerAtSettlement() {
        EventRule rule = EventRule.once("cheers",
                f -> false,   // never fires automatically
                new GameEvent("cheers", "The crowd erupts in applause.", "lyra"),
                new WorldEffect.SetFlag("crowd", "cheering"));
        CapturingModelClient model = new CapturingModelClient(
                // marcus raises a toast and triggers the event
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "trigger_event", args("{\"eventId\":\"cheers\"}")))),
                ModelResponse.text("A toast to the road-worn traveler!"),
                // lyra's event response
                ModelResponse.text("To the traveler!"));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                null, new EventEvaluator(List.of(rule)));

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus Let's have a toast!"));

        assertEquals(List.of("cheers"), turn.turn().triggeredEventIds());
        assertTrue(engine.world().flag("crowd").isPresent());
        assertEquals(2, turn.turn().responses().size());
        assertTrue(turn.turn().responses().get(1).eventDriven());
        assertTrue(turn.turn().responses().get(0).text().contains("toast"));
    }

    @Test
    @DisplayName("a manual trigger queued DURING an event response rolls to next settlement")
    void manualTriggerDuringEventResponseDefers() {
        EventRule first = EventRule.once("first",
                f -> false,
                new GameEvent("first", "The first event.", "lyra"));
        EventRule second = EventRule.once("second",
                f -> false,
                new GameEvent("second", "The second event.", null),
                new WorldEffect.SetFlag("second-fired", "yes"));
        CapturingModelClient model = new CapturingModelClient(
                // marcus triggers 'first' manually
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "trigger_event", args("{\"eventId\":\"first\"}")))),
                ModelResponse.text("Let's begin."),
                // lyra's event response ITSELF queues 'second'
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c2", "trigger_event", args("{\"eventId\":\"second\"}")))),
                ModelResponse.text("And now, something more!"),
                // turn 2: marcus plain reply; 'second' fires at that settlement
                ModelResponse.text("Quite a night."));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                null, new EventEvaluator(List.of(first, second)));

        TurnResult.Completed turn1 = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus begin"));
        assertEquals(List.of("first"), turn1.turn().triggeredEventIds(),
                "only 'first' this turn; 'second' was queued DURING the response");
        assertTrue(engine.world().flag("second-fired").isEmpty(),
                "'second' has NOT fired yet - no recursion, deferred");

        TurnResult.Completed turn2 = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus what a night"));
        assertTrue(turn2.turn().triggeredEventIds().contains("second"),
                "'second' fires at the next settlement");
        assertTrue(engine.world().flag("second-fired").isPresent());
    }

    // ============ Governance Chain (GM Backend) ============

    @Test
    @DisplayName("the Stage 9 governance chain audits every game-tool call - the GM backend")
    void governanceChainAuditsGameTools() {
        InMemoryAuditLogger audit = new InMemoryAuditLogger();
        Function<ToolRegistry, ToolExecutor> governance = registry ->
                GovernedToolExecutor.builder(new DefaultToolExecutor(registry))
                        .permissionChecker(new PermissionChecker(new ToolPolicy(ToolPermission.AUTO)))
                        .auditLogger(audit)
                        .build();
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag",
                                args("{\"key\":\"bard-mood\",\"value\":\"lively\"}")),
                        ToolCall.of("c2", "adjust_relationship",
                                args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("A fine evening indeed."));
        TurnEngine engine = new TurnEngine(
                new CharacterAgentFactory(model, store, governance),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);

        engine.playTurn("@marcus A round for the house!");

        List<AuditEvent> worldAudits = audit.getByTool("set_world_flag");
        List<AuditEvent> relationAudits = audit.getByTool("adjust_relationship");
        assertEquals(1, worldAudits.size(), "every world change is audited");
        assertEquals(1, relationAudits.size(), "every relationship change is audited");
        assertEquals(AuditEvent.AuditStatus.EXECUTED, worldAudits.get(0).status());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, relationAudits.get(0).status());
        assertTrue(relationAudits.get(0).args().contains("marcus"),
                "the GM backend sees WHOSE relationship changed by how much");
        // and the game itself worked through the governed chain
        assertEquals(53, engine.relationships().view("marcus").value());
        assertTrue(engine.world().flag("bard-mood").isPresent());
    }
}

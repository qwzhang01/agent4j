package io.github.qwzhang01.agent.tavern.turn;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ChatRole;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.world.WorldEffect;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.2 core test: the turn pipeline end to end.
 * <p>
 * Blueprint M16.2 acceptance under test:
 * mention routing (hit / miss), [world] (+[relationship]) sticky-note
 * injection verified at the model boundary, tool-submitted effects changing
 * the world, settled turn fields, turn advancement, per-character state
 * isolation, and the append-only log.
 */
class TurnEngineTest {

    /**
     * Capturing + scripted in one: records every ModelRequest, answers from a
     * response queue (tool calls first, then a text reply).
     */
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

    private TurnEngine engine(CapturingModelClient model, CharacterCard... cards) {
        return new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(cards), "game-1", WorldState.initial("tavern-hall"));
    }

    private ObjectNode flagArgs(String key, String value) {
        ObjectNode args = mapper.createObjectNode();
        args.put("key", key);
        args.put("value", value);
        return args;
    }

    // ============ Mention Routing ============

    @Test
    @DisplayName("a hit mention routes the turn to that character")
    void mentionRoutingHit() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Aye, traveler."));
        TurnEngine engine = engine(model, marcus, lyra);

        TurnResult result = engine.playTurn("@marcus A mug of mead, please.");

        TurnResult.Completed completed = assertInstanceOf(TurnResult.Completed.class, result);
        assertEquals("marcus", completed.turn().speakingCharacterId());
        assertEquals("Aye, traveler.", completed.turn().responses().get(0).text());
        assertEquals(1, model.requests().size(), "exactly one model call for one turn");
    }

    @Test
    @DisplayName("no mention at all = routing miss: no model call, no turn, no log entry")
    void mentionRoutingMissWithoutAt() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("never used"));
        TurnEngine engine = engine(model, marcus, lyra);

        TurnResult result = engine.playTurn("Just looking around.");

        TurnResult.RoutingMiss miss = assertInstanceOf(TurnResult.RoutingMiss.class, result);
        assertTrue(miss.availableCharacters().containsAll(List.of("marcus", "lyra")));
        assertEquals(0, model.requests().size(), "a routing miss must not burn a model call");
        assertEquals(0, engine.turnLog().size(), "a routing miss must not be logged");
        assertEquals(0, engine.world().turnCount(), "a routing miss must not advance the turn");
    }

    @Test
    @DisplayName("an @mention of an unknown character is still a miss (fail-closed routing)")
    void mentionRoutingMissUnknownCharacter() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("never used"));
        TurnEngine engine = engine(model, marcus, lyra);

        TurnResult result = engine.playTurn("@stranger Hello?");

        assertInstanceOf(TurnResult.RoutingMiss.class, result);
        assertEquals(0, model.requests().size());
    }

    // ============ Sticky-Note Injection ============

    @Test
    @DisplayName("[world] and [player] sticky notes land in the message the model sees")
    void worldStickyNoteInjection() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("ok"));
        TurnEngine engine = engine(model, marcus);

        engine.playTurn("@marcus A mug of mead, please.");

        ChatMessage userMsg = model.requests().get(0).messages().get(1);
        assertEquals(ChatRole.USER, userMsg.role());
        String text = userMsg.content();
        assertTrue(text.startsWith("[world] Turn 1 · tavern-hall\n"),
                "world snapshot leads the input, actual: " + text);
        assertTrue(text.contains("[player] @marcus A mug of mead, please."),
                "raw player input rides last, verbatim");
        assertFalse(text.contains("[relationship]"),
                "no describer wired -> no [relationship] line in M16.2 default wiring");
    }

    @Test
    @DisplayName("a wired relationship describer adds the [relationship] line (M16.3 preview)")
    void relationshipStickyNoteInjection() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("ok"));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, store),
                List.of(marcus), "game-1", WorldState.initial("tavern-hall"),
                characterId -> "affection 62 (WARM)");

        engine.playTurn("@marcus A mug of mead, please.");

        String text = model.requests().get(0).messages().get(1).content();
        assertTrue(text.contains("[relationship] affection 62 (WARM)\n"),
                "relationship snapshot sits between world and player, actual: " + text);
    }

    // ============ Tool -> World ============

    @Test
    @DisplayName("a tool call during the turn changes the world and lands in the turn record")
    void toolChangesWorld() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", flagArgs("bard-mood", "lively")))),
                ModelResponse.text("The bard starts playing."));
        TurnEngine engine = engine(model, marcus, lyra);

        TurnResult result = engine.playTurn("@lyra That song was lovely!");

        // world changed and is readable
        assertEquals(Optional.of("lively"), engine.world().flag("bard-mood"));
        assertTrue(engine.world().describe().contains("bard-mood=lively"));

        // and the effect is recorded in the settled turn
        TurnResult.Completed completed = assertInstanceOf(TurnResult.Completed.class, result);
        assertEquals(1, completed.turn().appliedEffects().size());
        WorldEffect.SetFlag flag = (WorldEffect.SetFlag) completed.turn().appliedEffects().get(0).effect();
        assertEquals("bard-mood", flag.key());
        assertEquals("lively", flag.value());
        // the character still got its final line out after the tool call
        assertEquals("The bard starts playing.", completed.turn().responses().get(0).text());
    }

    // ============ Turn Record & Advancement ============

    @Test
    @DisplayName("the settled turn carries complete, verbatim fields")
    void turnFieldsComplete() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("reply"));
        TurnEngine engine = engine(model, marcus, lyra);

        TurnResult.Completed completed = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus, hello there."));

        Turn turn = completed.turn();
        assertEquals(1, turn.turnNo());
        assertEquals("@marcus, hello there.", turn.playerInput());
        assertEquals("marcus", turn.speakingCharacterId());
        assertEquals(1, turn.responses().size());
        assertFalse(turn.responses().get(0).eventDriven());
        assertTrue(turn.triggeredEventIds().isEmpty(), "no events until M16.3");
        assertNotNull(turn.timestamp());
    }

    @Test
    @DisplayName("each played turn advances the counter and the [world] snapshot")
    void turnAdvances() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.text("one"), ModelResponse.text("two"));
        TurnEngine engine = engine(model, marcus);

        engine.playTurn("@marcus first");
        TurnResult.Completed second = assertInstanceOf(TurnResult.Completed.class,
                engine.playTurn("@marcus second"));

        assertEquals(2, engine.turnLog().size());
        assertEquals(2, second.turn().turnNo());
        assertEquals(2, engine.world().turnCount());
        // turn 2's request carries turn 1's history (continuation), so the
        // fresh injection is the LAST message, not index 1
        List<ChatMessage> secondRequest = model.requests().get(1).messages();
        assertEquals(4, secondRequest.size(), "[SYSTEM, USER(t1), ASSISTANT(t1), USER(t2)]");
        assertTrue(secondRequest.get(3).content()
                .startsWith("[world] Turn 2 · tavern-hall"));
    }

    @Test
    @DisplayName("per-character AgentStates stay isolated: talking to lyra never shows marcus history")
    void characterStateIsolation() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.text("marcus reply"), ModelResponse.text("lyra reply"));
        TurnEngine engine = engine(model, marcus, lyra);

        engine.playTurn("@marcus tell me a rumor");
        engine.playTurn("@lyra what do you think?");

        // lyra's request: [SYSTEM(her persona), USER(injected input)] - nothing of marcus's turn
        List<ChatMessage> lyraRequest = model.requests().get(1).messages();
        assertEquals(2, lyraRequest.size());
        assertTrue(lyraRequest.get(0).content().contains("Lyra"),
                "lyra's system prompt is her own persona");
        assertFalse(lyraRequest.stream().anyMatch(m ->
                        m.content() != null && m.content().contains("tell me a rumor")),
                "marcus's conversation must not leak into lyra's context");
        assertTrue(lyraRequest.get(1).content().contains("[player] @lyra what do you think?"));
    }

    // ============ Log ============

    @Test
    @DisplayName("the engine's log is append-only and shared with the facade (M16.5)")
    void engineLogAppendOnly() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.text("a"), ModelResponse.text("b"));
        TurnEngine engine = engine(model, marcus);

        engine.playTurn("@marcus one");
        engine.playTurn("@marcus two");
        TurnLog log = engine.turnLog();

        assertEquals(2, log.size());
        assertThrows(UnsupportedOperationException.class,
                () -> log.turns().remove(0));
    }

    // ============ Construction Guards ============

    @Test
    @DisplayName("an empty roster is rejected fail-fast")
    void emptyRosterRejected() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("x"));
        assertThrows(IllegalArgumentException.class,
                () -> new TurnEngine(new CharacterAgentFactory(model, store),
                        List.of(), "game-1", WorldState.initial("hall")));
    }

    @Test
    @DisplayName("null player input is rejected fail-fast")
    void nullInputRejected() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("x"));
        TurnEngine engine = engine(model, marcus);
        assertThrows(NullPointerException.class, () -> engine.playTurn(null));
    }
}

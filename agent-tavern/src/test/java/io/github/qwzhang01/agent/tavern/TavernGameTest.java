package io.github.qwzhang01.agent.tavern;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.security.AuditEvent;
import io.github.qwzhang01.agent.security.InMemoryAuditLogger;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventRule;
import io.github.qwzhang01.agent.tavern.event.GameEvent;
import io.github.qwzhang01.agent.tavern.replay.GameReplay;
import io.github.qwzhang01.agent.tavern.turn.TurnResult;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.5: the assembly facade - one builder, one game, everything
 * wired. The facade path always has a matrix and an evaluator (defaults),
 * governance plugs in with one line, and save/load round-trips through the
 * same assembly.
 */
class TavernGameTest {

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

    @TempDir
    Path root;

    private CharacterCard marcus;
    private CharacterCard lyra;

    @BeforeEach
    void setUp() {
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

    private TavernGame.Builder baseBuilder(CapturingModelClient model) {
        return TavernGame.builder()
                .modelClient(model)
                .memoryStore(new InMemoryMemoryStore())
                .addCard(marcus)
                .addCard(lyra)
                .gameId("game-1")
                .initialLocation("great-hall");
    }

    @Test
    @DisplayName("a built game plays turns end to end")
    void newGamePlaysTurns() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("Welcome in."));
        TavernGame game = baseBuilder(model).build();

        TurnResult result = game.playerSay("@marcus evening!");

        TurnResult.Completed completed = assertInstanceOf(TurnResult.Completed.class, result);
        assertEquals("marcus", completed.turn().speakingCharacterId());
        assertEquals("Welcome in.", completed.turn().responses().get(0).text());
        assertEquals(1, game.world().turnCount());
        assertEquals(List.of("marcus", "lyra"), game.characterIds());
    }

    @Test
    @DisplayName("the facade path always has a matrix and an evaluator (sensible defaults)")
    void defaultMatrixAndEvaluator() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("hi"));
        TavernGame game = baseBuilder(model).build();

        assertNotNull(game.relationships(), "matrix always present on the facade path");
        assertNotNull(game.eventEvaluator(), "evaluator always present on the facade path");
        assertEquals(50, game.relationships().view("marcus").value());
        assertTrue(game.eventEvaluator().firedEventIds().isEmpty());
    }

    @Test
    @DisplayName("builder validation fails fast with a clear message")
    void builderValidation() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("x"));

        assertThrows(IllegalStateException.class, () ->
                TavernGame.builder().addCard(marcus).gameId("g").build(),
                "missing modelClient");
        assertThrows(IllegalStateException.class, () ->
                TavernGame.builder().modelClient(model).addCard(marcus).build(),
                "missing gameId");
        assertThrows(IllegalStateException.class, () ->
                TavernGame.builder().modelClient(model).gameId("g").build(),
                "missing character cards");
    }

    @Test
    @DisplayName("[relationship] notes flow by default on the facade path")
    void relationshipNoteByDefault() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("hi"));
        TavernGame game = baseBuilder(model).build();

        game.playerSay("@marcus evening!");

        String injected = model.requests().get(0).messages().get(1).content();
        assertTrue(injected.contains("[relationship] affection 50 (NEUTRAL)"),
                "the facade wires the matrix into the notes, actual: " + injected);
    }

    @Test
    @DisplayName("governance(one logger) gives every game-tool call an audit trail")
    void governanceWiredThroughBuilder() {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "adjust_relationship",
                                args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("you seem decent."));
        InMemoryAuditLogger audit = new InMemoryAuditLogger();

        TavernGame game = baseBuilder(model).governance(audit).build();
        game.playerSay("@marcus a round for the house!");

        List<AuditEvent> events = audit.getByTool("adjust_relationship");
        assertEquals(1, events.size());
        assertEquals(AuditEvent.AuditStatus.EXECUTED, events.get(0).status());
        assertEquals(53, game.relationships().view("marcus").value());
    }

    @Test
    @DisplayName("save + builder.load(): same assembly, saved state, conversations continue")
    void saveLoadRoundTripViaBuilder() throws Exception {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "adjust_relationship",
                                args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("welcome."),
                ModelResponse.text("second."));
        TavernGame game = baseBuilder(model)
                .storeRoot(root)
                .build();
        game.playerSay("@marcus hello");
        game.playerSay("@marcus again");
        game.save();

        // reload with the SAME assembly
        CapturingModelClient secondModel = new CapturingModelClient(ModelResponse.text("of course I remember."));
        TavernGame reloaded = baseBuilder(secondModel)
                .storeRoot(root)
                .load();

        assertEquals(53, reloaded.relationships().view("marcus").value());
        assertEquals(game.world(), reloaded.world());

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                reloaded.playerSay("@marcus remember me?"));
        assertEquals(3, turn.turn().turnNo(), "turn numbering continues from the save");
        String context = secondModel.requests().get(0).messages().stream()
                .map(ChatMessage::content)
                .filter(c -> c != null)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(context.contains("@marcus hello"), "restored history is visible");
    }

    @Test
    @DisplayName("save() without a store root fails with a clear message")
    void saveWithoutStoreRootRejected() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("x"));
        TavernGame game = baseBuilder(model).build();

        assertThrows(IllegalStateException.class, game::save);
    }

    @Test
    @DisplayName("load() without a store root fails; load() of an unsaved game fails loud")
    void loadGuards() throws Exception {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("x"));

        assertThrows(IllegalStateException.class, () -> baseBuilder(model).load());
        assertThrows(java.nio.file.NoSuchFileException.class,
                () -> baseBuilder(model).storeRoot(root).load());
    }

    @Test
    @DisplayName("replay() mirrors this instance's turns; disk replay spans sessions")
    void replayViews() throws Exception {
        EventRule rule = EventRule.once("r1",
                f -> f.world().flag("mood").isPresent(),
                new GameEvent("cheers", "The crowd erupts.", "lyra"));
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", args("{\"key\":\"mood\",\"value\":\"lively\"}")))),
                ModelResponse.text("set."),
                ModelResponse.text("second."));
        TavernGame game = baseBuilder(model)
                .addRule(rule)
                .storeRoot(root)
                .build();
        game.playerSay("@marcus set the mood");   // flag set; event fires at settlement
        game.playerSay("@marcus quiet now");
        game.save();

        GameReplay memory = game.replay();
        assertEquals(2, memory.turnCount());
        assertTrue(memory.turns().get(0).triggeredEventIds().contains("cheers"));

        // disk replay: same history, readable without a running game
        GameReplay disk = game.replayFromDisk();
        assertEquals(2, disk.turnCount());
        assertEquals(memory.finalState().world(), disk.finalState().world());
        assertTrue(disk.describeTurn(1).contains("cheers") || disk.describeTurn(1).contains("mood"));
    }

    @Test
    @DisplayName("a game without governance still runs plain (backward-compatible default)")
    void noGovernanceStillWorks() {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("plain talk."));
        TavernGame game = baseBuilder(model).build();

        TurnResult.Completed completed = assertInstanceOf(TurnResult.Completed.class,
                game.playerSay("@lyra a word?"));
        assertEquals("plain talk.", completed.turn().responses().get(0).text());
    }
}

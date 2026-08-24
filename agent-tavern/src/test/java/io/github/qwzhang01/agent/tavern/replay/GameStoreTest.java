package io.github.qwzhang01.agent.tavern.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.memory.InMemoryMemoryStore;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.event.EventEvaluator;
import io.github.qwzhang01.agent.tavern.event.EventRule;
import io.github.qwzhang01.agent.tavern.event.GameEvent;
import io.github.qwzhang01.agent.tavern.relation.RelationshipMatrix;
import io.github.qwzhang01.agent.tavern.turn.TurnEngine;
import io.github.qwzhang01.agent.tavern.turn.TurnResult;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
 * Stage 16 M16.4: the save/reload round trip - blueprint D6 under test.
 * <p>
 * A save is a game snapshot, not a run checkpoint: world + relationships +
 * every character's dialogue history + event bookkeeping, all of which must
 * survive the round trip and let a reloaded game CONTINUE where the old one
 * left off.
 */
class GameStoreTest {

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

    private InMemoryMemoryStore memoryStore;
    private CharacterCard marcus;
    private CharacterCard lyra;

    @BeforeEach
    void setUp() {
        memoryStore = new InMemoryMemoryStore();
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

    private TurnEngine playedGame(CapturingModelClient model) {
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, memoryStore),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);
        return engine;
    }

    @Test
    @DisplayName("save creates the {gameId}/save.json + turn-log.jsonl layout")
    void saveCreatesLayout() throws Exception {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("hello"));
        TurnEngine engine = playedGame(model);
        engine.playTurn("@marcus hi");

        Path dir = new GameStore(root).save(engine);

        assertTrue(Files.isRegularFile(dir.resolve(GameStore.SAVE_FILE)));
        assertTrue(Files.isRegularFile(dir.resolve(GameStore.TURN_LOG_FILE)));
        assertEquals(root.resolve("game-1"), dir);
        assertTrue(new GameStore(root).exists("game-1"));
    }

    @Test
    @DisplayName("round trip: world, relationships, histories and fired events all survive")
    void roundTripSaveEquality() throws Exception {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", args("{\"key\":\"mood\",\"value\":\"lively\"}")),
                        ToolCall.of("c2", "adjust_relationship", args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("welcome, traveler."),
                ModelResponse.text("second beat."));
        TurnEngine engine = playedGame(model);
        engine.playTurn("@marcus hello");
        engine.playTurn("@marcus again");

        GameStore store = new GameStore(root);
        store.save(engine);
        SaveGame save = store.loadSave("game-1");

        assertEquals("game-1", save.gameId());
        assertEquals(engine.world(), save.world(), "world survives the round trip");
        assertEquals(engine.relationships().snapshot(), save.relationships());
        assertTrue(save.world().flag("mood").isPresent());
        assertEquals(53, save.relationships().get("marcus").value());
        assertEquals(engine.characterHistories(), save.characterHistories(),
                "every character's dialogue history survives (tool-call messages included)");
        assertTrue(save.characterHistories().get("marcus").size() > 2,
                "history includes system + injected user + assistant + tool messages");
    }

    @Test
    @DisplayName("the turn log's first line is the initial envelope")
    void jsonlFirstLineIsInitialEnvelope() throws Exception {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("hi"));
        TurnEngine engine = playedGame(model);
        engine.playTurn("@marcus hi");

        GameStore store = new GameStore(root);
        store.save(engine);

        List<String> lines = Files.readAllLines(root.resolve("game-1").resolve(GameStore.TURN_LOG_FILE));
        assertEquals(2, lines.size());
        ObjectNode first = (ObjectNode) mapper.readTree(lines.get(0));
        assertEquals("initial", first.path("kind").asText());
        assertEquals("hall", first.path("world").path("location").asText());
        assertTrue(first.path("relationships").isObject());
    }

    @Test
    @DisplayName("written turn-log bytes are stable under append: a later save extends the earlier one")
    void writtenBytesStableUnderAppend() throws Exception {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.text("one"), ModelResponse.text("two"), ModelResponse.text("three"));
        TurnEngine engine = playedGame(model);
        GameStore store = new GameStore(root);

        engine.playTurn("@marcus first");
        store.save(engine);
        byte[] earlier = Files.readAllBytes(root.resolve("game-1").resolve(GameStore.TURN_LOG_FILE));

        engine.playTurn("@marcus second");
        engine.playTurn("@marcus third");
        store.save(engine);
        byte[] later = Files.readAllBytes(root.resolve("game-1").resolve(GameStore.TURN_LOG_FILE));

        assertTrue(later.length > earlier.length);
        // prefix stability: the bytes written before never change
        for (int i = 0; i < earlier.length; i++) {
            if (earlier[i] != later[i]) {
                throw new AssertionError("byte " + i + " changed: written lines must be immutable");
            }
        }
    }

    @Test
    @DisplayName("loading a game that was never saved fails loud and specific")
    void loadMissingThrows() {
        GameStore store = new GameStore(root);

        assertThrows(java.nio.file.NoSuchFileException.class, () -> store.loadSave("ghost"));
        assertThrows(java.nio.file.NoSuchFileException.class, () -> store.loadReplay("ghost"));
    }

    @Test
    @DisplayName("a restored game continues conversations with full context (T6 of the blueprint)")
    void restoredGameContinuesConversations() throws Exception {
        CapturingModelClient firstModel = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "adjust_relationship", args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("welcome back!"));
        TurnEngine engine = playedGame(firstModel);
        engine.playTurn("@marcus shared a mead with me");

        GameStore store = new GameStore(root);
        store.save(engine);
        SaveGame save = store.loadSave("game-1");

        // reload: new engine over the SAVED world (not the original initial one)
        InMemoryMemoryStore freshMemory = new InMemoryMemoryStore();
        CapturingModelClient secondModel = new CapturingModelClient(ModelResponse.text("of course I remember."));
        RelationshipMatrix restoredMatrix = new RelationshipMatrix();
        restoredMatrix.restore(save.relationships());
        TurnEngine restored = new TurnEngine(new CharacterAgentFactory(secondModel, freshMemory),
                List.of(marcus, lyra), "game-1", save.world(), restoredMatrix, null);
        restored.restoreHistories(save.characterHistories());

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                restored.playTurn("@marcus remember me?"));

        assertEquals(2, turn.turn().turnNo(), "turn numbering continues from the save");
        assertEquals(53, restored.relationships().view("marcus").value(),
                "restored relationship limit budget is fresh for the new turn");
        String context = secondModel.requests().get(0).messages().stream()
                .map(ChatMessage::content)
                .filter(c -> c != null)
                .reduce("", (a, b) -> a + "\n" + b);
        assertTrue(context.contains("shared a mead with me"),
                "the restored history is visible to the model - conversations continue");
        assertTrue(context.contains("affection 53 (NEUTRAL)"),
                "relationship notes come from the restored matrix");
    }

    @Test
    @DisplayName("once-bookkeeping survives a reload: a fired event stays fired")
    void onceBookkeepingSurvivesRestore() throws Exception {
        EventRule rule = EventRule.once("r1",
                f -> f.world().flag("cheer").isPresent(),
                new GameEvent("cheers", "The crowd erupts.", null));
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", args("{\"key\":\"cheer\",\"value\":\"yes\"}")))),
                ModelResponse.text("cheers!"),
                ModelResponse.text("again."));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, memoryStore),
                List.of(marcus), "game-1", WorldState.initial("hall"),
                null, new EventEvaluator(List.of(rule)));
        engine.playTurn("@marcus cheer");   // event fires

        GameStore store = new GameStore(root);
        store.save(engine);
        SaveGame save = store.loadSave("game-1");
        assertTrue(save.firedEventIds().contains("cheers"));

        // reload with the same rule table + restored bookkeeping
        CapturingModelClient secondModel = new CapturingModelClient(ModelResponse.text("quiet."));
        EventEvaluator restoredEvaluator = new EventEvaluator(List.of(rule));
        restoredEvaluator.restore(save.firedEventIds());
        TurnEngine restored = new TurnEngine(new CharacterAgentFactory(secondModel, new InMemoryMemoryStore()),
                List.of(marcus), "game-1", save.world(), null, restoredEvaluator);

        TurnResult.Completed turn = assertInstanceOf(TurnResult.Completed.class,
                restored.playTurn("@marcus cheer again"));

        assertTrue(turn.turn().triggeredEventIds().isEmpty(),
                "the condition still holds but the event already happened - once is once");
    }

    @Test
    @DisplayName("a multimodal message in history fails loud instead of saving silently wrong")
    void multimodalHistoryFailsLoud() throws Exception {
        CapturingModelClient model = new CapturingModelClient(ModelResponse.text("hi"));
        TurnEngine engine = playedGame(model);
        engine.playTurn("@marcus hi");
        // poison the history with a multimodal message (v1 cannot save it)
        engine.restoreHistories(java.util.Map.of("marcus", List.of(
                io.github.qwzhang01.agent.core.model.ChatMessage.userWithImage("pic", "http://x/y.png"))));

        assertThrows(IllegalArgumentException.class, () -> new GameStore(root).save(engine));
    }
}

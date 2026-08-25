package io.github.qwzhang01.agent.tavern.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.model.ToolCall;
import io.github.qwzhang01.agent.memory.store.InMemoryMemoryStore;
import io.github.qwzhang01.agent.tavern.character.CharacterAgentFactory;
import io.github.qwzhang01.agent.tavern.character.CharacterCard;
import io.github.qwzhang01.agent.tavern.relation.RelationshipMatrix;
import io.github.qwzhang01.agent.tavern.turn.TurnEngine;
import io.github.qwzhang01.agent.tavern.world.WorldState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.4: walk the recording, never re-run the model - blueprint D7
 * under test.
 * <p>
 * stateAt(n) rebuilds world + relationships at any point in history from the
 * recorded effects/changes alone; the replay's final state must equal the
 * save's state (the two files check each other); corrupted logs fail loud
 * with line numbers (the Stage 14 discipline).
 */
class GameReplayerTest {

    private static final class CapturingModelClient implements ModelClient {

        private final Queue<ModelResponse> responses = new LinkedBlockingQueue<>();

        CapturingModelClient(ModelResponse... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
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
    Path dir;

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

    /** Three turns: T1 flag + relationship, T2 relationship only, T3 another flag. */
    private GameReplay threeTurnReplay() throws Exception {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", args("{\"key\":\"mood\",\"value\":\"lively\"}")),
                        ToolCall.of("c2", "adjust_relationship", args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("first reply."),
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c3", "adjust_relationship", args("{\"characterId\":\"marcus\",\"delta\":3}")))),
                ModelResponse.text("second reply."),
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c4", "set_world_flag", args("{\"key\":\"quarrel\",\"value\":\"brewing\"}")))),
                ModelResponse.text("third reply."));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, memoryStore),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);
        engine.playTurn("@marcus one");
        engine.playTurn("@marcus two");
        engine.playTurn("@marcus three");

        GameStore store = new GameStore(dir);
        store.save(engine);
        return store.loadReplay("game-1");
    }

    // ============ stateAt ============

    @Test
    @DisplayName("stateAt(n) rebuilds the world and relationships at each point in history")
    void stateAtTimePoints() throws Exception {
        GameReplay replay = threeTurnReplay();

        // before any turn
        GameReplay.ReplaySnapshot t0 = replay.stateAt(0);
        assertEquals(0, t0.world().turnCount());
        assertTrue(t0.world().flag("mood").isEmpty());
        assertEquals(50, t0.relationship("marcus").value());

        // after turn 1: flag set, relationship +3
        GameReplay.ReplaySnapshot t1 = replay.stateAt(1);
        assertEquals(1, t1.world().turnCount());
        assertEquals(java.util.Optional.of("lively"), t1.world().flag("mood"));
        assertEquals(53, t1.relationship("marcus").value());

        // after turn 2: relationship +3 more, flag untouched
        GameReplay.ReplaySnapshot t2 = replay.stateAt(2);
        assertEquals(56, t2.relationship("marcus").value());
        assertTrue(t2.world().flag("quarrel").isEmpty(), "not yet - turn 3 hasn't happened");

        // after turn 3: the second flag lands - the final state
        GameReplay.ReplaySnapshot t3 = replay.stateAt(3);
        assertEquals(3, t3.world().turnCount());
        assertEquals(java.util.Optional.of("brewing"), t3.world().flag("quarrel"));
        assertEquals(56, t3.relationship("marcus").value());

        assertEquals(3, replay.turnCount());
        assertEquals(t3.world(), replay.finalState().world());
    }

    @Test
    @DisplayName("the replay's final state equals the save file's state - the two files check each other")
    void finalStateMatchesSave() throws Exception {
        CapturingModelClient model = new CapturingModelClient(
                ModelResponse.toolCalls(List.of(
                        ToolCall.of("c1", "set_world_flag", args("{\"key\":\"mood\",\"value\":\"lively\"}")),
                        ToolCall.of("c2", "adjust_relationship", args("{\"characterId\":\"lyra\",\"delta\":-3}")))),
                ModelResponse.text("reply."));
        TurnEngine engine = new TurnEngine(new CharacterAgentFactory(model, memoryStore),
                List.of(marcus, lyra), "game-1", WorldState.initial("hall"),
                new RelationshipMatrix(), null);
        engine.playTurn("@lyra a rough crowd tonight");

        GameStore store = new GameStore(dir);
        store.save(engine);
        SaveGame save = store.loadSave("game-1");
        GameReplay replay = store.loadReplay("game-1");

        assertEquals(save.world(), replay.finalState().world(),
                "world: replayed history ends exactly where the save says");
        assertEquals(save.relationships(), replay.finalState().relationships(),
                "relationships: replayed changes end exactly where the save says");
    }

    @Test
    @DisplayName("describeTurn is a human-readable review of one beat")
    void describeTurnHumanReadable() throws Exception {
        GameReplay replay = threeTurnReplay();

        String text = replay.describeTurn(1);

        assertTrue(text.contains("Turn 1"));
        assertTrue(text.contains("@marcus one"));
        assertTrue(text.contains("marcus: \"first reply.\""));
        assertTrue(text.contains("world: set mood=lively"));
        assertTrue(text.contains("relationship: marcus +3 (50 -> 53)"));
    }

    @Test
    @DisplayName("stateAt out of range is rejected; describeTurn is 1-based")
    void boundsAreChecked() throws Exception {
        GameReplay replay = threeTurnReplay();

        assertThrows(IllegalArgumentException.class, () -> replay.stateAt(4));
        assertThrows(IllegalArgumentException.class, () -> replay.stateAt(-1));
        assertThrows(IllegalArgumentException.class, () -> replay.describeTurn(0));
        assertThrows(IllegalArgumentException.class, () -> replay.describeTurn(4));
    }

    // ============ Integrity (fail loud, with line numbers) ============

    private Path writeLog(String... lines) throws Exception {
        Path file = dir.resolve("broken.jsonl");
        Files.write(file, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static final String INITIAL =
            "{\"kind\":\"initial\",\"world\":{\"turnCount\":0,\"location\":\"hall\",\"flags\":{}},\"relationships\":{}}";

    private static final String TURN_1 =
            "{\"kind\":\"turn\",\"turnNo\":1,\"playerInput\":\"x\",\"speakingCharacterId\":\"m\","
                    + "\"responses\":[],\"appliedEffects\":[],\"relationshipChanges\":[],"
                    + "\"triggeredEventIds\":[],\"timestamp\":\"2026-08-24T10:00:00Z\"}";

    private static final String TURN_3 =
            "{\"kind\":\"turn\",\"turnNo\":3,\"playerInput\":\"z\",\"speakingCharacterId\":\"m\","
                    + "\"responses\":[],\"appliedEffects\":[],\"relationshipChanges\":[],"
                    + "\"triggeredEventIds\":[],\"timestamp\":\"2026-08-24T10:02:00Z\"}";

    @Test
    @DisplayName("a log whose first line is not the initial envelope is rejected")
    void missingInitialRejected() throws Exception {
        Path file = writeLog(TURN_1);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GameReplayer().load(file));
        assertTrue(e.getMessage().contains("initial envelope"));
    }

    @Test
    @DisplayName("a turn-number gap is rejected with the offending line number")
    void turnGapRejectedWithLineNumber() throws Exception {
        Path file = writeLog(INITIAL, TURN_1, TURN_3);

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GameReplayer().load(file));
        assertTrue(e.getMessage().contains("line 3"), "message: " + e.getMessage());
        assertTrue(e.getMessage().contains("expected turnNo 2"));
    }

    @Test
    @DisplayName("a malformed JSON line is rejected with the line number")
    void badLineRejectedWithLineNumber() throws Exception {
        Path file = writeLog(INITIAL, "this is not json {");

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new GameReplayer().load(file));
        assertTrue(e.getMessage().contains("line 2"), "message: " + e.getMessage());
    }

    @Test
    @DisplayName("an empty log is rejected")
    void emptyLogRejected() throws Exception {
        Path file = writeLog("");

        assertThrows(IllegalArgumentException.class, () -> new GameReplayer().load(file));
    }

    @Test
    @DisplayName("a minimal well-formed log loads: initial + turns, no surprises")
    void minimalLogLoads() throws Exception {
        Path file = writeLog(INITIAL, TURN_1);

        GameReplay replay = new GameReplayer().load(file);

        assertEquals(1, replay.turnCount());
        assertEquals(1, replay.turns().get(0).turnNo());
        assertEquals("hall", replay.stateAt(0).world().location());
    }
}

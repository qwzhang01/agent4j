package io.github.qwzhang01.agent.tavern.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.tavern.relation.Relationship;
import io.github.qwzhang01.agent.tavern.turn.Turn;
import io.github.qwzhang01.agent.tavern.turn.TurnEngine;
import io.github.qwzhang01.agent.tavern.world.WorldState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Saves and loads games (Stage 16 M16.4, blueprint D6).
 * <p>
 * Directory layout: {@code {root}/{gameId}/save.json} (the full-state
 * snapshot for resuming) and {@code {root}/{gameId}/turn-log.jsonl} (the
 * append-only history for replay). Two files, two purposes: the save file is
 * the fast path back into the game; the turn log is the reviewable history.
 * Their consistency is checkable: the replay's final state must equal the
 * save's state.
 * <p>
 * The turn log is written whole on each save, but its BYTES are stable
 * under append: the same turns serialize to the same lines, so a later save
 * is a byte-prefix extension of an earlier one (written lines never change).
 */
public final class GameStore {

    public static final String SAVE_FILE = "save.json";
    public static final String TURN_LOG_FILE = "turn-log.jsonl";

    private final Path rootDir;
    private final ObjectMapper mapper = new ObjectMapper();

    public GameStore(Path rootDir) {
        this.rootDir = Objects.requireNonNull(rootDir, "rootDir must not be null");
    }

    /**
     * Save a running game (whole-file writes; byte-stable under append).
     *
     * @return the game's directory
     */
    public Path save(TurnEngine engine) throws IOException {
        Objects.requireNonNull(engine, "engine must not be null");
        Path dir = rootDir.resolve(engine.gameId());
        Files.createDirectories(dir);

        writeSave(dir.resolve(SAVE_FILE), snapshotOf(engine));
        writeTurnLog(dir.resolve(TURN_LOG_FILE), engine);
        return dir;
    }

    /** Whether a save exists for a game. */
    public boolean exists(String gameId) {
        return Files.isRegularFile(rootDir.resolve(gameId).resolve(SAVE_FILE));
    }

    /**
     * Load the full-state snapshot for resuming a game.
     *
     * @throws java.nio.file.NoSuchFileException when nothing was saved for the game
     */
    public SaveGame loadSave(String gameId) throws IOException {
        Path file = rootDir.resolve(gameId).resolve(SAVE_FILE);
        if (!Files.isRegularFile(file)) {
            throw new java.nio.file.NoSuchFileException(file.toString(), null,
                    "no save exists for game '" + gameId + "'");
        }
        return readSave(file);
    }

    /**
     * Load the step-through replay of a game's history.
     *
     * @throws java.nio.file.NoSuchFileException when no turn log exists for the game
     */
    public GameReplay loadReplay(String gameId) throws IOException {
        Path file = rootDir.resolve(gameId).resolve(TURN_LOG_FILE);
        if (!Files.isRegularFile(file)) {
            throw new java.nio.file.NoSuchFileException(file.toString(), null,
                    "no turn log exists for game '" + gameId + "'");
        }
        return new GameReplayer().load(file);
    }

    // ============ Snapshot Assembly ============

    private SaveGame snapshotOf(TurnEngine engine) {
        Map<String, Relationship> relationships = engine.relationships() != null
                ? engine.relationships().snapshot()
                : Map.of();
        return new SaveGame(
                engine.gameId(),
                engine.world(),
                relationships,
                engine.characterHistories(),
                engine.eventEvaluator() != null
                        ? engine.eventEvaluator().firedEventIds()
                        : java.util.Set.of());
    }

    // ============ save.json ============

    private void writeSave(Path file, SaveGame save) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("gameId", save.gameId());
        root.set("world", ReplayCodec.worldToJson(save.world(), mapper));
        root.set("relationships", ReplayCodec.relationshipsToJson(save.relationships(), mapper));
        ObjectNode histories = root.putObject("character_histories");
        save.characterHistories().forEach((id, msgs) ->
                histories.set(id, ReplayCodec.messagesToJson(msgs, mapper)));
        var firedArr = root.putArray("fired_event_ids");
        save.firedEventIds().forEach(firedArr::add);
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
    }

    private SaveGame readSave(Path file) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        String gameId = root.path("gameId").asText();
        WorldState world = ReplayCodec.worldFromJson(root.path("world"));
        Map<String, Relationship> relationships =
                ReplayCodec.relationshipsFromJson(root.path("relationships"));
        java.util.Map<String, List<ChatMessage>> histories = new java.util.LinkedHashMap<>();
        root.path("character_histories").fields().forEachRemaining(e ->
                histories.put(e.getKey(), ReplayCodec.messagesFromJson(e.getValue())));
        java.util.Set<String> fired = new java.util.LinkedHashSet<>();
        root.path("fired_event_ids").forEach(id -> fired.add(id.asText()));
        return new SaveGame(gameId, world, relationships, histories, fired);
    }

    // ============ turn-log.jsonl ============

    private void writeTurnLog(Path file, TurnEngine engine) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(ReplayCodec.initialLineToJson(
                engine.initialWorld(), engine.initialRelationships(), mapper).toString());
        for (Turn turn : engine.turnLog().turns()) {
            lines.add(ReplayCodec.turnToJson(turn, mapper).toString());
        }
        Files.write(file, String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8));
    }
}

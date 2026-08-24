package io.github.qwzhang01.agent.tavern.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.tavern.turn.Turn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a turn log into a step-through {@link GameReplay} (Stage 16 M16.4).
 * <p>
 * Load-time integrity checks, fail-loud with line numbers (the Stage 14
 * discipline): the first line must be the initial envelope; turn numbers
 * must run 1..n consecutively; every line must parse. A replay is a
 * historical record - a corrupted one must be rejected, not best-efforted.
 */
public final class GameReplayer {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param turnLogJsonl the JSONL file written by {@link GameStore}
     */
    public GameReplay load(Path turnLogJsonl) throws IOException {
        List<String> lines = Files.readAllLines(turnLogJsonl, StandardCharsets.UTF_8);
        List<String> content = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank()) {
                content.add(line);
            }
        }
        if (content.isEmpty()) {
            throw new IllegalArgumentException(
                    turnLogJsonl + ": empty turn log - the first line must be the initial envelope");
        }

        JsonNode first = parseLine(content.get(0), 1);
        if (!ReplayCodec.isInitialLine(first)) {
            throw new IllegalArgumentException(
                    turnLogJsonl + " line 1: expected the initial envelope, got kind='"
                            + first.path("kind").asText(null) + "'");
        }
        var initialWorld = ReplayCodec.worldFromJson(first.path("world"));
        var initialRelationships = ReplayCodec.relationshipsFromJson(first.path("relationships"));

        List<Turn> turns = new ArrayList<>();
        for (int i = 1; i < content.size(); i++) {
            int lineNumber = i + 1;
            JsonNode node = parseLine(content.get(i), lineNumber);
            if (!ReplayCodec.isTurnLine(node)) {
                throw new IllegalArgumentException(
                        turnLogJsonl + " line " + lineNumber
                                + ": expected kind='turn', got kind='" + node.path("kind").asText(null) + "'");
            }
            Turn turn = ReplayCodec.turnFromJson(node);
            if (turn.turnNo() != i) {
                throw new IllegalArgumentException(
                        turnLogJsonl + " line " + lineNumber
                                + ": expected turnNo " + i + ", got " + turn.turnNo()
                                + " - turn numbers must be consecutive from 1");
            }
            turns.add(turn);
        }
        return new GameReplay(initialWorld, initialRelationships, turns);
    }

    private JsonNode parseLine(String line, int lineNumber) {
        try {
            return mapper.readTree(line);
        } catch (Exception e) {
            throw new IllegalArgumentException("line " + lineNumber
                    + ": not valid JSON (" + e.getMessage() + ")");
        }
    }
}

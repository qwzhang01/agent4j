package io.github.qwzhang01.agent.tavern.relation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.qwzhang01.agent.core.tool.Tool;
import io.github.qwzhang01.agent.core.tool.ToolException;

import java.util.Objects;
import java.util.function.IntSupplier;

/**
 * {@code adjust_relationship} - the character's handle on the relationship
 * domain (Stage 16 M16.3, blueprint D4: "influence is a tool").
 * <p>
 * Same submitter pattern as {@code SetWorldFlagTool}: the tool holds the
 * matrix and a turn supplier, not the engine. Numeric bounds (the per-turn
 * accumulated limit) live HERE, inside the tool's domain; permission and
 * audit live in the governance chain wrapping the executor - the two-layer
 * split of blueprint D4: governance decides IF the call may happen, the
 * domain validates WHAT it contains.
 * <p>
 * A rejected adjustment is NOT an exception: the rejection text becomes the
 * tool result the model reads in the ReAct loop, and the model self-corrects
 * (continue the scene naturally instead of brute-forcing affection) - the
 * Stage 2 tool-error contract applied as game design.
 */
public final class AdjustRelationshipTool implements Tool {

    public static final String NAME = "adjust_relationship";

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "characterId": { "type": "string", "description": "Whose relationship with the player changes, e.g. 'marcus'" },
                "delta":       { "type": "integer", "description": "Change amount, negative for worse, e.g. 3 or -2" }
              },
              "required": ["characterId", "delta"]
            }
            """;

    private final RelationshipMatrix matrix;
    private final IntSupplier currentTurn;
    private final java.util.function.Consumer<RelationshipMatrix.ApplyResult.Applied> appliedSink;

    /**
     * @param matrix      the game's relationship matrix (single write path)
     * @param currentTurn supplies the turn number the engine is playing
     * @param appliedSink receives every ACCEPTED adjustment (the engine records
     *                    it into the turn for replay); rejected adjustments
     *                    change nothing and are never submitted
     */
    public AdjustRelationshipTool(RelationshipMatrix matrix, IntSupplier currentTurn,
                                  java.util.function.Consumer<RelationshipMatrix.ApplyResult.Applied> appliedSink) {
        this.matrix = Objects.requireNonNull(matrix, "matrix must not be null");
        this.currentTurn = Objects.requireNonNull(currentTurn, "currentTurn must not be null");
        this.appliedSink = Objects.requireNonNull(appliedSink, "appliedSink must not be null");
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Adjust your relationship with the player based on how the conversation "
                + "feels for your character (e.g. +2 when they show you genuine kindness, "
                + "-3 when they insult you). Per-turn change is limited; if the tool "
                + "reports a limit, accept it and continue the scene naturally.";
    }

    @Override
    public String getParametersSchema() {
        return SCHEMA;
    }

    @Override
    public String execute(JsonNode arguments) throws ToolException {
        if (arguments == null || !arguments.hasNonNull("characterId")
                || !arguments.hasNonNull("delta") || !arguments.get("delta").isInt()) {
            throw new ToolException("adjust_relationship requires 'characterId' (string) "
                    + "and 'delta' (integer)");
        }
        String characterId = arguments.get("characterId").asText();
        int delta = arguments.get("delta").asInt();

        RelationshipMatrix.ApplyResult result = matrix.apply(characterId, delta, currentTurn.getAsInt());
        // Java 17: instanceof patterns (switch patterns are Java 21)
        if (result instanceof RelationshipMatrix.ApplyResult.Applied a) {
            appliedSink.accept(a);
            return "Relationship with " + a.characterId() + ": " + a.before().value()
                    + " -> " + a.after().value() + " (" + a.after().tier() + ").";
        }
        RelationshipMatrix.ApplyResult.Rejected r =
                (RelationshipMatrix.ApplyResult.Rejected) result;
        return "[REJECTED] " + r.reason()
                + ". Continue the scene naturally instead of forcing the change.";
    }
}

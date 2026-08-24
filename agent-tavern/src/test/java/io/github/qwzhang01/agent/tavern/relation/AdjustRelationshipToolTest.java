package io.github.qwzhang01.agent.tavern.relation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.qwzhang01.agent.core.tool.ToolException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.3: the relationship tool's contract - success text, rejection
 * text (game flow, not exceptions), and the applied-sink record stream.
 */
class AdjustRelationshipToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("an accepted adjustment confirms in text and submits to the sink")
    void acceptedConfirmAndSubmit() throws Exception {
        RelationshipMatrix matrix = new RelationshipMatrix();
        List<RelationshipMatrix.ApplyResult.Applied> submitted = new ArrayList<>();
        AdjustRelationshipTool tool = new AdjustRelationshipTool(matrix, () -> 1, submitted::add);

        String result = tool.execute(mapper.readTree(
                "{\"characterId\":\"marcus\",\"delta\":3}"));

        assertTrue(result.contains("50 -> 53"));
        assertTrue(result.contains("(NEUTRAL)"));
        assertEquals(1, submitted.size());
        assertEquals("marcus", submitted.get(0).characterId());
        assertEquals(3, submitted.get(0).requestedDelta());
    }

    @Test
    @DisplayName("a rejected adjustment returns [REJECTED] text and submits nothing")
    void rejectedTextNoSubmit() throws Exception {
        RelationshipMatrix matrix = new RelationshipMatrix();
        List<RelationshipMatrix.ApplyResult.Applied> submitted = new ArrayList<>();
        AdjustRelationshipTool tool = new AdjustRelationshipTool(matrix, () -> 1, submitted::add);

        String result = tool.execute(mapper.readTree(
                "{\"characterId\":\"marcus\",\"delta\":10}"));

        assertTrue(result.startsWith("[REJECTED]"), "the model must read the failure");
        assertTrue(result.contains("±5"), "the reason states the limit");
        assertTrue(result.contains("Continue the scene naturally"),
                "the rejection coaches the model to self-correct");
        assertEquals(0, submitted.size(), "nothing changed, nothing recorded");
        assertEquals(50, matrix.view("marcus").value());
    }

    @Test
    @DisplayName("missing or non-integer arguments are ToolExceptions")
    void badArgumentsRejected() {
        AdjustRelationshipTool tool = new AdjustRelationshipTool(
                new RelationshipMatrix(), () -> 1, a -> { });

        assertThrows(ToolException.class, () -> tool.execute(null));
        assertThrows(ToolException.class,
                () -> tool.execute(mapper.createObjectNode().put("characterId", "marcus")));
        assertThrows(ToolException.class, () -> tool.execute(
                mapper.createObjectNode().put("characterId", "marcus").put("delta", "three")));
    }

    @Test
    @DisplayName("tool metadata is model-facing and honest")
    void metadata() {
        AdjustRelationshipTool tool = new AdjustRelationshipTool(
                new RelationshipMatrix(), () -> 1, a -> { });

        assertEquals("adjust_relationship", tool.getName());
        assertTrue(tool.getDescription().contains("limited"));
        assertTrue(tool.getParametersSchema().contains("\"delta\""));
    }
}

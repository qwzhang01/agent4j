package io.github.qwzhang01.agent.tavern.relation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 16 M16.3: the relationship matrix with its per-turn accumulated
 * limiter - blueprint D4 under test ("governance is balance").
 * <p>
 * The salami-slicing defense is the headline: +3 four times must NOT reach +12.
 */
class RelationshipMatrixTest {

    // ============ View ============

    @Test
    @DisplayName("unseen characters read as neutral 50, tracked ones as applied")
    void viewSemantics() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertEquals(50, matrix.view("marcus").value());
        assertEquals(Relationship.Tier.NEUTRAL, matrix.view("marcus").tier());

        matrix.apply("marcus", 5, 1);
        assertEquals(55, matrix.view("marcus").value());
        assertEquals(50, matrix.view("lyra").value(), "other characters untouched");
    }

    // ============ The Limiter ============

    @Test
    @DisplayName("a legal single adjustment applies")
    void legalChangeApplies() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        RelationshipMatrix.ApplyResult result = matrix.apply("marcus", 3, 1);

        RelationshipMatrix.ApplyResult.Applied applied =
                assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, result);
        assertEquals(50, applied.before().value());
        assertEquals(53, applied.after().value());
        assertEquals(3, applied.requestedDelta());
        assertEquals(53, matrix.view("marcus").value());
    }

    @Test
    @DisplayName("a single oversized adjustment is rejected with a reason (fail-closed)")
    void singleOversizeRejected() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        RelationshipMatrix.ApplyResult result = matrix.apply("marcus", 10, 1);

        RelationshipMatrix.ApplyResult.Rejected rejected =
                assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, result);
        assertTrue(rejected.reason().contains("±5"));
        assertEquals(50, matrix.view("marcus").value(), "nothing changed");
    }

    @Test
    @DisplayName("salami slicing blocked: +3 then +3 is rejected (net would be +6)")
    void accumulatedLimitBlocksSlicing() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 3, 1));
        RelationshipMatrix.ApplyResult second = matrix.apply("marcus", 3, 1);

        assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, second);
        assertEquals(53, matrix.view("marcus").value(), "still just the first +3");
    }

    @Test
    @DisplayName("up to the exact limit is allowed; one more is not")
    void exactLimitBoundary() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 5, 1));
        var oneMoreResult = matrix.apply("marcus", 1, 1);
        assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, oneMoreResult);
        assertEquals(55, matrix.view("marcus").value());
    }

    @Test
    @DisplayName("negative swings count against the same budget (net semantics)")
    void negativeSwingsShareBudget() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", -4, 1));
        // net is now -4; another -2 would make -6 -> rejected
        assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, matrix.apply("marcus", -2, 1));
        // but a +1 (net -3) is fine, and back toward zero
        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 1, 1));
        assertEquals(47, matrix.view("marcus").value());
    }

    @Test
    @DisplayName("a new turn resets the budget (slow drift across turns is legit)")
    void newTurnResetsBudget() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 5, 1));
        assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, matrix.apply("marcus", 1, 1));

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 5, 2));
        assertEquals(60, matrix.view("marcus").value());
    }

    @Test
    @DisplayName("budgets are per character within a turn")
    void budgetPerCharacter() {
        RelationshipMatrix matrix = new RelationshipMatrix();

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 5, 1));
        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("lyra", 5, 1));
        assertEquals(55, matrix.view("marcus").value());
        assertEquals(55, matrix.view("lyra").value());
    }

    // ============ Clamping ============

    @Test
    @DisplayName("values clamp to 0-100 but the budget is charged by the request")
    void clampingChargesFullRequest() {
        RelationshipMatrix matrix = new RelationshipMatrix();
        matrix.apply("marcus", 5, 1);
        matrix.apply("marcus", 5, 2);
        matrix.apply("marcus", 5, 3);
        matrix.apply("marcus", 5, 4);   // 70
        matrix.apply("marcus", 5, 5);   // 75
        matrix.apply("marcus", 5, 6);   // 80
        matrix.apply("marcus", 5, 7);   // 85
        matrix.apply("marcus", 5, 8);   // 90
        matrix.apply("marcus", 5, 9);   // 95

        RelationshipMatrix.ApplyResult clamped = matrix.apply("marcus", 5, 10);
        RelationshipMatrix.ApplyResult.Applied applied =
                assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, clamped);
        assertEquals(95, applied.before().value());
        assertEquals(100, applied.after().value(), "clamped at the ceiling");
        assertEquals(100, matrix.view("marcus").value());
    }

    // ============ Snapshot & Guards ============

    @Test
    @DisplayName("snapshot is an immutable copy of tracked relationships")
    void snapshotSemantics() {
        RelationshipMatrix matrix = new RelationshipMatrix();
        matrix.apply("marcus", 3, 1);

        assertEquals(1, matrix.snapshot().size());
        assertThrows(UnsupportedOperationException.class,
                () -> matrix.snapshot().put("lyra", Relationship.initial()));
    }

    @Test
    @DisplayName("blank characterId and negative turn are rejected fail-fast")
    void guards() {
        RelationshipMatrix matrix = new RelationshipMatrix();
        assertThrows(IllegalArgumentException.class, () -> matrix.view(" "));
        assertThrows(IllegalArgumentException.class, () -> matrix.apply(null, 3, 1));
        assertThrows(IllegalArgumentException.class, () -> matrix.apply("marcus", 3, -1));
    }

    @Test
    @DisplayName("a custom policy widens the budget")
    void customPolicy() {
        RelationshipMatrix matrix = new RelationshipMatrix(new RelationshipPolicy(10));

        assertInstanceOf(RelationshipMatrix.ApplyResult.Applied.class, matrix.apply("marcus", 8, 1));
        assertInstanceOf(RelationshipMatrix.ApplyResult.Rejected.class, matrix.apply("marcus", 3, 1));
    }

    @Test
    @DisplayName("policy below 1 is rejected; tier boundaries derive from value")
    void policyAndTierEdges() {
        assertThrows(IllegalArgumentException.class, () -> new RelationshipPolicy(0));

        assertEquals(Relationship.Tier.STRANGER, new Relationship(19, 0).tier());
        assertEquals(Relationship.Tier.COLD, new Relationship(20, 0).tier());
        assertEquals(Relationship.Tier.NEUTRAL, new Relationship(54, 0).tier());
        assertEquals(Relationship.Tier.WARM, new Relationship(55, 0).tier());
        assertEquals(Relationship.Tier.FRIEND, new Relationship(75, 0).tier());
        assertEquals(Relationship.Tier.DEVOTED, new Relationship(90, 0).tier());
        assertEquals("affection 62 (WARM)", new Relationship(62, 3).describe());
        assertThrows(IllegalArgumentException.class, () -> new Relationship(101, 0));
        assertThrows(IllegalArgumentException.class, () -> new Relationship(-1, 0));
    }
}

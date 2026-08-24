package io.github.qwzhang01.agent.observability.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RouteDecisionTest {

    @Test
    @DisplayName("construction: accessors + record equality")
    void constructionAndEquality() {
        RouteDecision d = new RouteDecision("cheap", "remaining 18% < 25% threshold");

        assertEquals("cheap", d.modelId());
        assertEquals("remaining 18% < 25% threshold", d.reason());
        assertEquals(d, new RouteDecision("cheap", "remaining 18% < 25% threshold"));
    }

    @Test
    @DisplayName("blank modelId is rejected")
    void blankModelIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision("  ", "healthy"));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision(null, "healthy"));
    }

    @Test
    @DisplayName("null/blank reason is rejected - a decision without a reason is unauditable (D6)")
    void blankReasonRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision("premium", ""));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision("premium", "   "));
        assertThrows(IllegalArgumentException.class, () -> new RouteDecision("premium", null));
    }
}

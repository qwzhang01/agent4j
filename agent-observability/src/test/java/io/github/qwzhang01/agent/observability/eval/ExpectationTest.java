package io.github.qwzhang01.agent.observability.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpectationTest {

    private static final Expectation.Outcome OUTCOME =
            new Expectation.Outcome("抱歉，我道歉并修正了答案", 340, 2);

    // ============ ExactMatch ============

    @Test
    @DisplayName("ExactMatch: equal text passes, different text fails, null text counts as empty")
    void exactMatch() {
        Expectation exact = new Expectation.ExactMatch("抱歉，我道歉并修正了答案");

        assertTrue(exact.test(OUTCOME));
        assertFalse(exact.test(new Expectation.Outcome("other text", 0, 0)));
        assertTrue(new Expectation.ExactMatch("").test(new Expectation.Outcome(null, 0, 0)),
                "null outcome text normalizes to empty - exact match of \"\" holds");
        assertThrows(NullPointerException.class, () -> new Expectation.ExactMatch(null));
    }

    // ============ Contains ============

    @Test
    @DisplayName("Contains: substring passes, missing fails; describe carries the fragment")
    void contains() {
        Expectation contains = new Expectation.Contains("道歉");

        assertTrue(contains.test(OUTCOME));
        assertFalse(contains.test(new Expectation.Outcome("no apology here", 0, 0)));
        assertEquals("contains \"道歉\"", contains.describe());
        assertThrows(NullPointerException.class, () -> new Expectation.Contains(null));
    }

    // ============ MaxTokens ============

    @Test
    @DisplayName("MaxTokens: inclusive ceiling (landing on it passes), over fails, negative rejected")
    void maxTokens() {
        Expectation max = new Expectation.MaxTokens(340);

        assertTrue(max.test(OUTCOME), "340 <= 340 - landing on the line passes");
        assertFalse(new Expectation.MaxTokens(339).test(OUTCOME));
        assertEquals("at most 340 tokens", max.describe());
        assertThrows(IllegalArgumentException.class, () -> new Expectation.MaxTokens(-1));
    }

    // ============ ToolCallCount ============

    @Test
    @DisplayName("ToolCallCount: exact count passes, off-by-one fails, negative rejected")
    void toolCallCount() {
        Expectation count = new Expectation.ToolCallCount(2);

        assertTrue(count.test(OUTCOME));
        assertFalse(new Expectation.ToolCallCount(3).test(OUTCOME));
        assertFalse(new Expectation.ToolCallCount(0).test(OUTCOME));
        assertEquals("exactly 2 tool calls", count.describe());
        assertThrows(IllegalArgumentException.class, () -> new Expectation.ToolCallCount(-1));
    }

    // ============ describe consistency ============

    @Test
    @DisplayName("describe(): every variant renders a human-readable one-liner")
    void describeAll() {
        assertEquals("exact match \"42\"", new Expectation.ExactMatch("42").describe());
        assertEquals("at most 100 tokens", new Expectation.MaxTokens(100).describe());
        assertEquals("exactly 0 tool calls", new Expectation.ToolCallCount(0).describe());
    }
}

package io.github.qwzhang01.agent.coding.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Stage 17 M17.4: the fix budget record - defaults, validation, and the zero-budget
 * edge (one failure is already too many).
 */
class FixLoopPolicyTest {

    @Test
    @DisplayName("default budget is 3 failed runs")
    void defaultPolicy() {
        assertEquals(3, FixLoopPolicy.DEFAULT.maxFixIterations());
    }

    @Test
    @DisplayName("budget below 1 is rejected: the initial test run must always be possible")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new FixLoopPolicy(0));
        assertThrows(IllegalArgumentException.class, () -> new FixLoopPolicy(-1));
        assertEquals(1, new FixLoopPolicy(1).maxFixIterations());
        assertEquals(10, new FixLoopPolicy(10).maxFixIterations());
    }
}

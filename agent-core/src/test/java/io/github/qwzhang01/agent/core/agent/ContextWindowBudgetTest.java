package io.github.qwzhang01.agent.core.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ContextWindowBudget} (KP2).
 */
class ContextWindowBudgetTest {

    // historyBudget arithmetic

    @Test
    @DisplayName("historyBudget() = total - system - tools - output")
    void historyBudget_subtractsThreeSlots() {
        ContextWindowBudget budget = ContextWindowBudget.of(10_000, 1_000, 2_000, 1_500);
        assertEquals(5_500, budget.historyBudget());
    }

    @Test
    @DisplayName("historyBudget() == 0 when slots exactly consume the window")
    void historyBudget_zero_whenSlotsConsumeEntireWindow() {
        ContextWindowBudget budget = ContextWindowBudget.of(10_000, 3_000, 4_000, 3_000);
        assertEquals(0, budget.historyBudget());
    }

    // forWindow preset

    @Test
    @DisplayName("forWindow(128_000): historyBudget is ~55% of total")
    void forWindow_128k_historyIsAbout55Percent() {
        ContextWindowBudget budget = ContextWindowBudget.window128k();
        assertEquals(128_000, budget.totalWindowTokens());
        // 10% system + 15% tools + 20% output = 45%; history = 55%
        assertTrue(budget.historyBudget() > 0, "history budget must be positive");
        assertTrue(budget.historyBudget() < budget.totalWindowTokens(),
                "history budget must be less than total");
        // historyBudget ≥ 50% of total (generous lower bound)
        assertTrue(budget.historyBudget() >= budget.totalWindowTokens() / 2,
                "history budget should be at least 50% of total");
    }

    @Test
    @DisplayName("forWindow(8_000): all slots positive, historyBudget > 0")
    void forWindow_8k_allSlotsPositive() {
        ContextWindowBudget budget = ContextWindowBudget.window8k();
        assertEquals(8_000, budget.totalWindowTokens());
        assertTrue(budget.systemReserve() > 0);
        assertTrue(budget.toolSchemaReserve() > 0);
        assertTrue(budget.outputHeadroom() > 0);
        assertTrue(budget.historyBudget() > 0);
    }

    @Test
    @DisplayName("negative totalWindowTokens is rejected at construction")
    void of_negativeTotalTokens_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowBudget.of(-1, 100, 100, 100));
    }

    @Test
    @DisplayName("slots exceeding total are rejected at construction")
    void of_slotsExceedTotal_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowBudget.of(1_000, 500, 400, 300));
    }

    @Test
    @DisplayName("negative slot is rejected at construction")
    void of_negativeSlot_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> ContextWindowBudget.of(1_000, -1, 100, 100));
    }

    @Test
    @DisplayName("toString includes all five numbers")
    void toString_includesAllSlots() {
        ContextWindowBudget budget = ContextWindowBudget.of(10_000, 1_000, 1_500, 2_000);
        String str = budget.toString();
        assertTrue(str.contains("10000"), "total in toString");
        assertTrue(str.contains("1000"), "system in toString");
        assertTrue(str.contains("1500"), "tools in toString");
        assertTrue(str.contains("2000"), "output in toString");
        assertTrue(str.contains(String.valueOf(budget.historyBudget())), "history in toString");
    }
}

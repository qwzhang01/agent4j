package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BudgetAwareRouterTest {

    private static final ModelRequest REQ = ModelRequest.builder()
            .model("any")
            .addMessage(ChatMessage.user("hi"))
            .build();

    private final BudgetAwareRouter router = new BudgetAwareRouter("premium", "cheap", 25);

    // ============ three budget bands ============

    @Test
    @DisplayName("healthy budget: premium, reason carries the remaining percent")
    void healthyGoesPremium() {
        RouteDecision d = router.route(REQ, ModelRouter.BudgetSnapshot.of(5_000, 10_000));

        assertEquals("premium", d.modelId());
        assertTrue(d.reason().contains("50%"), "reason must carry the number: " + d.reason());
        assertTrue(d.reason().contains("healthy"));
    }

    @Test
    @DisplayName("below threshold: cheap, reason carries remaining % AND the threshold (audit trail)")
    void constrainedGoesCheap() {
        RouteDecision d = router.route(REQ, ModelRouter.BudgetSnapshot.of(1_800, 10_000));

        assertEquals("cheap", d.modelId());
        assertTrue(d.reason().contains("18%"), "reason must carry remaining: " + d.reason());
        assertTrue(d.reason().contains("25%"), "reason must carry threshold: " + d.reason());
    }

    @Test
    @DisplayName("landing exactly ON the threshold stays premium (denial is for overdraft, not for landing on the line)")
    void exactlyAtThresholdStaysPremium() {
        RouteDecision d = router.route(REQ, ModelRouter.BudgetSnapshot.of(2_500, 10_000));

        assertEquals("premium", d.modelId(), "25% remaining with threshold 25 is NOT below");
    }

    @Test
    @DisplayName("unlimited snapshot: premium, never downgrade on an uncapped budget")
    void unlimitedStaysPremium() {
        ModelRouter.BudgetSnapshot unlimited = ModelRouter.BudgetSnapshot.unlimited();

        assertTrue(unlimited.isUnlimited());
        assertEquals(100, unlimited.remainingPercent());

        RouteDecision d = router.route(REQ, unlimited);
        assertEquals("premium", d.modelId());
        assertTrue(d.reason().contains("unlimited"));
    }

    @Test
    @DisplayName("exhausted budget (remaining 0): BudgetExhaustedException - cheap cannot help when the budget is GONE")
    void exhaustedThrows() {
        BudgetExhaustedException e = assertThrows(BudgetExhaustedException.class,
                () -> router.route(REQ, ModelRouter.BudgetSnapshot.of(0, 10_000)));

        assertEquals(0, e.remaining());
        assertEquals(10_000, e.limit());
        assertTrue(e.getMessage().contains("0 of 10000"), "message carries the numbers: " + e.getMessage());
    }

    @Test
    @DisplayName("tiny-but-nonzero remaining: downgrade, not throw (0% floors below the threshold)")
    void tinyRemainingDowngrades() {
        RouteDecision d = router.route(REQ, ModelRouter.BudgetSnapshot.of(5, 10_000));

        assertEquals("cheap", d.modelId());
        assertTrue(d.reason().contains("0%"), "5/10000 floors to 0%: " + d.reason());
    }

    @Test
    @DisplayName("remaining percent floors: 2499/10000 = 24% < 25 -> cheap (int percent, documented)")
    void percentFloors() {
        RouteDecision d = router.route(REQ, ModelRouter.BudgetSnapshot.of(2_499, 10_000));

        assertEquals("cheap", d.modelId());
        assertTrue(d.reason().contains("24%"));
    }

    // ============ guards ============

    @Test
    @DisplayName("constructor guards: blank tier ids, percent out of 1-99")
    void constructorGuards() {
        assertThrows(IllegalArgumentException.class, () -> new BudgetAwareRouter(" ", "cheap", 25));
        assertThrows(IllegalArgumentException.class, () -> new BudgetAwareRouter("premium", null, 25));
        assertThrows(IllegalArgumentException.class, () -> new BudgetAwareRouter("premium", "cheap", 0));
        assertThrows(IllegalArgumentException.class, () -> new BudgetAwareRouter("premium", "cheap", 100));
    }

    @Test
    @DisplayName("snapshot of() validates: limit > 0, 0 <= remaining <= limit (router never sees an impossible ledger)")
    void snapshotValidation() {
        assertThrows(IllegalArgumentException.class, () -> ModelRouter.BudgetSnapshot.of(1, 0));
        assertThrows(IllegalArgumentException.class, () -> ModelRouter.BudgetSnapshot.of(-1, 10_000));
        assertThrows(IllegalArgumentException.class, () -> ModelRouter.BudgetSnapshot.of(10_001, 10_000));
        assertDoesNotThrow(() -> ModelRouter.BudgetSnapshot.of(0, 10_000));
        assertDoesNotThrow(() -> ModelRouter.BudgetSnapshot.of(10_000, 10_000));
    }

    @Test
    @DisplayName("null budget snapshot rejected")
    void nullBudgetRejected() {
        assertThrows(NullPointerException.class, () -> router.route(REQ, null));
    }
}

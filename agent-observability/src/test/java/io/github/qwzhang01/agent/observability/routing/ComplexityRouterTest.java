package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class ComplexityRouterTest {

    private static ModelRequest ofMessages(int count, String lastUserText) {
        ModelRequest.Builder b = ModelRequest.builder().model("m");
        IntStream.range(0, count - 1).forEach(i -> b.addMessage(ChatMessage.user("msg " + i)));
        b.addMessage(ChatMessage.user(lastUserText));
        return b.build();
    }

    private static ModelRequest single(String userText) {
        return ModelRequest.builder()
                .model("m")
                .addMessage(ChatMessage.user(userText))
                .build();
    }

    // ============ signal 1: message count ============

    @Test
    @DisplayName("short thread routes cheap")
    void shortThreadCheap() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        RouteDecision d = router.route(ofMessages(3, "hi"), ModelRouter.BudgetSnapshot.unlimited());
        assertEquals("cheap", d.modelId());
        assertTrue(d.reason().contains("3 messages"), d.reason());
    }

    @Test
    @DisplayName("deep thread (>= threshold messages) routes premium")
    void deepThreadPremium() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        RouteDecision d = router.route(ofMessages(8, "hi"), ModelRouter.BudgetSnapshot.unlimited());
        assertEquals("premium", d.modelId());
        assertTrue(d.reason().contains("8 messages >= 8"), d.reason());
    }

    // ============ signal 2: complexity marker ============

    @Test
    @DisplayName("complexity marker in the last user message forces premium even on a short thread")
    void markerForcesPremium() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        RouteDecision d = router.route(single("please analyze this data"), ModelRouter.BudgetSnapshot.unlimited());
        assertEquals("premium", d.modelId());
        assertTrue(d.reason().contains("analyze"), d.reason());
    }

    @Test
    @DisplayName("markers only count in the LAST user message, not earlier turns")
    void markerOnlyLastUser() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        ModelRequest req = ModelRequest.builder()
                .model("m")
                .addMessage(ChatMessage.user("please analyze this"))
                .addMessage(ChatMessage.assistant("done"))
                .addMessage(ChatMessage.user("thanks"))
                .build();
        RouteDecision d = router.route(req, ModelRouter.BudgetSnapshot.unlimited());
        assertEquals("cheap", d.modelId());
    }

    @Test
    @DisplayName("chinese markers also trigger premium")
    void chineseMarkersTrigger() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        RouteDecision d = router.route(single("帮我审查一下这段代码"), ModelRouter.BudgetSnapshot.unlimited());
        assertEquals("premium", d.modelId());
        assertTrue(d.reason().contains("审查"), d.reason());
    }

    // ============ custom configuration ============

    @Test
    @DisplayName("custom threshold and markers are honored")
    void customConfig() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap", 2, List.of("hard"));
        assertEquals("premium", router.route(single("hard question"), ModelRouter.BudgetSnapshot.unlimited()).modelId());
        assertEquals("cheap", router.route(single("easy question"), ModelRouter.BudgetSnapshot.unlimited()).modelId());
        // 2 messages: threshold is >= 2
        assertEquals("premium", router.route(ofMessages(2, "still"), ModelRouter.BudgetSnapshot.unlimited()).modelId());
    }

    // ============ guards ============

    @Test
    @DisplayName("constructor guards: blank model ids, bad threshold, null markers")
    void constructorGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> new ComplexityRouter(" ", "cheap"));
        assertThrows(IllegalArgumentException.class,
                () -> new ComplexityRouter("premium", null));
        assertThrows(IllegalArgumentException.class,
                () -> new ComplexityRouter("premium", "cheap", 0, List.of("x")));
        assertThrows(NullPointerException.class,
                () -> new ComplexityRouter("premium", "cheap", 5, null));
    }

    // ============ budget view is accepted but ignored ============

    @Test
    @DisplayName("capped budget snapshot does not change complexity routing (signals only, no economics)")
    void budgetIgnored() {
        ComplexityRouter router = new ComplexityRouter("premium", "cheap");
        RouteDecision d = router.route(single("hi"), ModelRouter.BudgetSnapshot.of(50, 100));
        assertEquals("cheap", d.modelId(), "complexity is signal routing; budget economics is BudgetAwareRouter's job");
    }
}

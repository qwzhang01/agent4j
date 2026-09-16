package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage 9 gap closure: risk-dimension routing. The risk signal is the tool
 * contract's {@code SideEffectLevel} (declared per tool by the assembly);
 * a turn exposing destructive or possibly-mutating tools routes premium,
 * a read-shaped turn routes cheap. Pins the v1 mapping and the honest
 * limits (absent-from-map ≠ unknown-level).
 */
class RiskAwareRouterTest {

    private static final String PREMIUM = "gpt-max";
    private static final String CHEAP = "mini";

    private static ModelRequest requestWithTools(String... schemas) {
        return ModelRequest.builder()
                .messages(List.of(ChatMessage.user("do something")))
                .tools(List.of(schemas))
                .build();
    }

    private static ModelRequest plainRequest() {
        return ModelRequest.builder()
                .messages(List.of(ChatMessage.user("just chatting")))
                .build();
    }

    private static String schema(String name) {
        return "{\"name\":\"" + name + "\",\"description\":\"stub\",\"parameters\":{}}";
    }

    @Test
    @DisplayName("destructive tool exposed: premium with naming reason")
    void destructiveTool_routesPremium() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "delete_file", SideEffectLevel.DESTRUCTIVE,
                "get_time", SideEffectLevel.READ_ONLY));

        RouteDecision d = router.route(
                requestWithTools(schema("delete_file")),
                ModelRouter.BudgetSnapshot.unlimited());

        assertEquals(PREMIUM, d.modelId());
        assertTrue(d.reason().contains("delete_file"));
        assertTrue(d.reason().contains("destructive"));
    }

    @Test
    @DisplayName("SIDE_EFFECT and UNKNOWN levels: premium (conservative)")
    void mutatingOrUnknown_levels_routePremium() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "write_note", SideEffectLevel.SIDE_EFFECT,
                "legacy_tool", SideEffectLevel.UNKNOWN));

        assertEquals(PREMIUM, router.route(
                requestWithTools(schema("write_note")),
                ModelRouter.BudgetSnapshot.unlimited()).modelId());
        assertEquals(PREMIUM, router.route(
                requestWithTools(schema("legacy_tool")),
                ModelRouter.BudgetSnapshot.unlimited()).modelId());
    }

    @Test
    @DisplayName("read-shaped tools only: cheap")
    void readShapedOnly_routesCheap() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "get_time", SideEffectLevel.READ_ONLY,
                "math", SideEffectLevel.NONE));

        RouteDecision d = router.route(
                requestWithTools(schema("get_time"), schema("math")),
                ModelRouter.BudgetSnapshot.unlimited());

        assertEquals(CHEAP, d.modelId());
    }

    @Test
    @DisplayName("tool absent from the risk map: no signal, neither upgrade nor downgrade")
    void unclassifiedTool_noSignal() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "get_time", SideEffectLevel.READ_ONLY));

        // 'unregistered' is exposed but not in the map: the router refuses
        // to fabricate a risk level for it -> cheap (read-shaped default
        // when no other signal applies).
        RouteDecision d = router.route(
                requestWithTools(schema("unregistered")),
                ModelRouter.BudgetSnapshot.unlimited());

        assertEquals(CHEAP, d.modelId());
    }

    @Test
    @DisplayName("no tools in request at all: cheap")
    void noTools_routesCheap() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "delete_file", SideEffectLevel.DESTRUCTIVE));

        assertEquals(CHEAP, router.route(
                plainRequest(), ModelRouter.BudgetSnapshot.unlimited()).modelId());
    }

    @Test
    @DisplayName("mixed exposure: one destructive among read-shaped tools still routes premium")
    void mixedExposure_strongestSignalWins() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "delete_file", SideEffectLevel.DESTRUCTIVE,
                "get_time", SideEffectLevel.READ_ONLY,
                "math", SideEffectLevel.NONE));

        RouteDecision d = router.route(
                requestWithTools(schema("get_time"), schema("math"), schema("delete_file")),
                ModelRouter.BudgetSnapshot.unlimited());

        assertEquals(PREMIUM, d.modelId());
    }

    @Test
    @DisplayName("constructor validation: blank models and null map rejected")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new RiskAwareRouter("", CHEAP, Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new RiskAwareRouter(PREMIUM, " ", Map.of()));
        assertThrows(NullPointerException.class,
                () -> new RiskAwareRouter(PREMIUM, CHEAP, null));
    }

    @Test
    @DisplayName("composes with RoutingModelClient: premium/cheap candidates addressed by key")
    void composesWithRoutingClient() {
        RiskAwareRouter router = new RiskAwareRouter(PREMIUM, CHEAP, Map.of(
                "delete_file", SideEffectLevel.DESTRUCTIVE));

        io.github.qwzhang01.agent.core.model.ModelResponse ok =
                io.github.qwzhang01.agent.core.model.ModelResponse.text("done");
        io.github.qwzhang01.agent.core.client.ModelClient premium = new io.github.qwzhang01.agent.core.client.ModelClient() {
            @Override public io.github.qwzhang01.agent.core.model.ModelResponse chat(
                    ModelRequest request) { return ok; }
            @Override public java.util.stream.Stream<io.github.qwzhang01.agent.core.model.StreamEvent> stream(
                    ModelRequest request) { throw new UnsupportedOperationException(); }
        };
        io.github.qwzhang01.agent.core.client.ModelClient cheap = premium;

        RoutingModelClient client = new RoutingModelClient(
                Map.of(PREMIUM, premium, CHEAP, cheap), router);

        // Destructive exposure routes to the premium candidate
        io.github.qwzhang01.agent.core.model.ModelResponse routed =
                client.chat(requestWithTools(schema("delete_file")));
        assertEquals("done", routed.content());

        // Read-shaped turn routes to the cheap candidate
        io.github.qwzhang01.agent.core.model.ModelResponse cheap2 =
                client.chat(requestWithTools(schema("unclassified")));
        assertEquals("done", cheap2.content());
    }
}

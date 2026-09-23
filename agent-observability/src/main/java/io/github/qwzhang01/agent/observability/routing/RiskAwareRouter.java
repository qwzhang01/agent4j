package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.tool.contract.SideEffectLevel;

import java.util.Map;
import java.util.Objects;

/**
 * Risk-aware routing (Stage 9 gap closure): the RISK dimension of model
 * routing — a high-stakes step deserves the strongest model, a read-only
 * step can run cheap. The risk signal is the tool contract's
 * {@link SideEffectLevel} the assembly declares per tool name.
 * <p>
 * WHERE this sits (same shape as {@link ComplexityRouter}): pre-call
 * signals belong in a {@link ModelRouter} strategy plugged into the
 * EXISTING {@link RoutingModelClient} — zero changes to the decorator.
 * The router reads the request's declared tool calls; when any requested
 * tool maps to a high-risk level, the call goes premium.
 * <p>
 * WHY the request alone (honest limits): a {@link ModelRequest} carries
 * tool SCHEMAS, not the model's eventual tool choices — the model decides
 * tools mid-conversation and the router runs BEFORE the call. So the
 * pre-call proxy is the request's exposed tool set: if the conversation
 * exposes DESTRUCTIVE tools at all, treat the turn as high-stakes. This
 * over-estimates risk (a turn that merely discusses a destructive tool
 * still routes premium) — the deliberate trade: mis-routing a risky turn
 * cheap costs correctness, mis-routing a safe turn premium costs only
 * money. Same asymmetry as {@code ComplexityRouter}: mis-routes are
 * expected and safe in one direction.
 * <p>
 * v1 mapping (deliberately static and explainable):
 * <ul>
 *   <li>{@code DESTRUCTIVE} → premium (irreversible blast radius)</li>
 *   <li>{@code SIDE_EFFECT} / {@code UNKNOWN} → premium (UNKNOWN is the
 *       contract's conservative level — a legacy tool that never declared
 *       its effects is assumed to mutate state)</li>
 *   <li>{@code NONE} / {@code READ_ONLY} → cheap unless another signal
 *       (budget, complexity) says otherwise</li>
 * </ul>
 * Tools absent from the map carry no risk signal — the router neither
 * upgrades nor downgrades for them; composition with other routers
 * ({@code BudgetAwareRouter}, {@code ComplexityRouter}) covers their
 * dimensions. {@code UNKNOWN} absent-from-map and unknown-level are
 * deliberately DIFFERENT: a tool in the map with level UNKNOWN says "the
 * contract admits it mutates"; a tool not in the map says "this assembly
 * did not classify it" — routing on absent data would fabricate risk.
 */
public final class RiskAwareRouter implements ModelRouter {

    private final String premiumModel;
    private final String cheapModel;
    private final Map<String, SideEffectLevel> toolRisk;

    /**
     * @param premiumModel candidate key for the strong/cautious tier
     * @param cheapModel   candidate key for the cheap tier
     * @param toolRisk     tool name → declared side-effect level; the risk
     *                     signal source. Copy is defensive; empty map =
     *                     this router never upgrades (all calls cheap when
     *                     no other signal applies — see {@link #route})
     */
    public RiskAwareRouter(String premiumModel, String cheapModel,
                           Map<String, SideEffectLevel> toolRisk) {
        if (premiumModel == null || premiumModel.isBlank()) {
            throw new IllegalArgumentException("premiumModel must not be null or blank");
        }
        if (cheapModel == null || cheapModel.isBlank()) {
            throw new IllegalArgumentException("cheapModel must not be null or blank");
        }
        Objects.requireNonNull(toolRisk, "toolRisk");
        this.premiumModel = premiumModel;
        this.cheapModel = cheapModel;
        this.toolRisk = Map.copyOf(toolRisk);
    }

    @Override
    public RouteDecision route(ModelRequest request, BudgetSnapshot budget) {
        Objects.requireNonNull(budget, "budget");

        for (String toolName : exposedTools(request)) {
            SideEffectLevel level = toolRisk.get(toolName);
            if (level == null) {
                continue; // not classified by this assembly: no signal
            }
            if (level == SideEffectLevel.DESTRUCTIVE) {
                return new RouteDecision(premiumModel,
                        "destructive tool '" + toolName
                                + "' exposed - high stakes, premium");
            }
            if (level == SideEffectLevel.SIDE_EFFECT || level == SideEffectLevel.UNKNOWN) {
                return new RouteDecision(premiumModel,
                        level + " tool '" + toolName
                                + "' exposed - possibly mutating, premium");
            }
        }
        return new RouteDecision(cheapModel,
                "no high-risk tools exposed (of " + toolRisk.size()
                        + " classified) - read-shaped turn, cheap");
    }

    /**
     * Extract the declared tool names from the request's JSON schemas. A
     * schema is the registry's tool definition JSON; its {@code "name"}
     * field is the tool identity. Malformed schemas (no name field) are
     * skipped — routing never fails on bad tool JSON.
     */
    private static java.util.List<String> exposedTools(ModelRequest request) {
        if (request.tools() == null || request.tools().isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String schema : request.tools()) {
            String name = toolNameOf(schema);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    /** Parse the tool name out of a schema JSON string, or null. */
    private static String toolNameOf(String schema) {
        if (schema == null || schema.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
            return node.path("name").asText(null);
        } catch (Exception e) {
            return null; // malformed schema: no signal, never a routing failure
        }
    }
}

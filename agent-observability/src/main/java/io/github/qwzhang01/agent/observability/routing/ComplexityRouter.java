package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import java.util.Objects;

/**
 * Pre-call complexity router (E2): judge the request BEFORE spending any
 * tokens, and route big asks to premium / small asks to cheap.
 * <p>
 * WHERE this sits (decision 25): pre-call signals belong in a
 * {@link ModelRouter} strategy - this class plugs into the EXISTING
 * {@link RoutingModelClient} with zero changes to it, proving the decorator
 * shape carries pre-call routing. Post-call signals (response quality) are
 * {@link CascadeModelClient}'s business, not this one's.
 * <p>
 * v1 signals (deliberately cheap and explainable, no embedding, no classifier):
 * <ul>
 *   <li><b>message count</b> - accumulated conversation depth: long threads
 *       carry implicit complexity (state tracking, references, nuance); the
 *       default threshold routes 8+ messages to premium</li>
 *   <li><b>explicit task marker</b> - the LAST user message containing a
 *       complexity marker from the configured list (default: "analyze",
 *       "design", "refactor", "architect", "审查"...) forces premium</li>
 * </ul>
 * <p>
 * This is the honest pre-call maximum: without seeing the response you cannot
 * know if an answer is good - you can only know if the question LOOKS hard.
 * Mis-routes are expected and safe (both directions): a hard ask landing on
 * cheap produces a bad answer, but that is exactly what the cascade above it
 * catches; an easy ask landing on premium only costs money, not quality.
 */
public final class ComplexityRouter implements ModelRouter {

    private final String premiumModel;
    private final String cheapModel;
    private final int messageThreshold;
    private final java.util.List<String> complexityMarkers;

    /**
     * Blueprint defaults: premium above 8 messages or on a complexity marker.
     */
    public ComplexityRouter(String premiumModel, String cheapModel) {
        this(premiumModel, cheapModel, 8, java.util.List.of(
                "analyze", "design", "refactor", "architect", "review", "evaluate",
                "审查", "设计", "重构", "分析", "架构"));
    }

    /**
     * @param premiumModel      candidate key for the premium tier
     * @param cheapModel        candidate key for the cheap tier
     * @param messageThreshold  route premium when the message count is >= this
     * @param complexityMarkers substrings in the last user message that force premium
     */
    public ComplexityRouter(String premiumModel, String cheapModel,
                            int messageThreshold, java.util.List<String> complexityMarkers) {
        if (premiumModel == null || premiumModel.isBlank()) {
            throw new IllegalArgumentException("premiumModel must not be null or blank");
        }
        if (cheapModel == null || cheapModel.isBlank()) {
            throw new IllegalArgumentException("cheapModel must not be null or blank");
        }
        if (messageThreshold < 1) {
            throw new IllegalArgumentException("messageThreshold must be >= 1: " + messageThreshold);
        }
        Objects.requireNonNull(complexityMarkers, "complexityMarkers");
        this.premiumModel = premiumModel;
        this.cheapModel = cheapModel;
        this.messageThreshold = messageThreshold;
        this.complexityMarkers = java.util.List.copyOf(complexityMarkers);
    }

    @Override
    public RouteDecision route(ModelRequest request, BudgetSnapshot budget) {
        Objects.requireNonNull(budget, "budget");

        int messages = request.messages() == null ? 0 : request.messages().size();
        String lastUser = lastUserText(request);

        // signal 1: conversation depth
        if (messages >= messageThreshold) {
            return new RouteDecision(premiumModel,
                    messages + " messages >= " + messageThreshold
                            + " threshold - deep thread, premium");
        }

        // signal 2: explicit complexity marker in the latest user turn
        if (lastUser != null && !lastUser.isBlank()) {
            String lower = lastUser.toLowerCase();
            for (String marker : complexityMarkers) {
                if (lower.contains(marker.toLowerCase())) {
                    return new RouteDecision(premiumModel,
                            "complexity marker '" + marker + "' in last user message - premium");
                }
            }
        }

        return new RouteDecision(cheapModel,
                messages + " messages < " + messageThreshold
                        + " and no complexity marker - small ask, cheap");
    }

    // TODO: [multimodal last-user extraction] - @avin
    private static String lastUserText(ModelRequest request) {
        if (request.messages() == null) {
            return null;
        }
        for (int i = request.messages().size() - 1; i >= 0; i--) {
            var msg = request.messages().get(i);
            if (msg.role() == io.github.qwzhang01.agent.core.model.ChatRole.USER) {
                return msg.content();
            }
        }
        return null;
    }
}

package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * ModelClient decorator (Stage 18 D6): ask the router before every call, then
 * forward to the chosen candidate - nothing else.
 * <p>
 * WHO gets called is the {@link ModelRouter} strategy's business (v1:
 * {@link BudgetAwareRouter}); this class only resolves the decision's
 * {@code modelId} against the candidate map and delegates. Zero-touch
 * forwarding is a tested contract: the selected client receives the ORIGINAL
 * request instance (no rewrite of {@code request.model()} - v1 candidates are
 * addressed by logical key; a provider needing its own model string sets it in
 * its own client) and its response/stream instance is returned as-is.
 * <p>
 * Exception direction (contrast with the metrics decorators, which are side
 * channels): routing IS the main path. Router exceptions
 * ({@link io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException},
 * or a custom router's failure) and delegate exceptions propagate UNTOUCHED -
 * no catching, no masking. This is availability-of-honesty, not resilience:
 * the caller must learn the call was refused.
 * <p>
 * Composition with Stage 1 (D6, both layers keep their job):
 * {@code Routing(Fallback(premium, cheap))} - the outer layer picks by budget,
 * the inner layer chains on failure. Cheap models die too; routing does not
 * replace fallback, it decides what to try first.
 * <p>
 * Recommended wiring: UNDER {@code ObservingModelClient}
 * ({@code Observing(Routing(Fallback(...)))}) so the metrics row carries the
 * outermost latency the caller perceived.
 */
public final class RoutingModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingModelClient.class);

    private final Map<String, ModelClient> candidates;
    private final ModelRouter router;
    private final Supplier<ModelRouter.BudgetSnapshot> budgetSource;

    /**
     * Route without any budget view: every call sees
     * {@link ModelRouter.BudgetSnapshot#unlimited()} (a budget-unaware router
     * of your own, or a fixed-tier setup).
     */
    public RoutingModelClient(Map<String, ModelClient> candidates, ModelRouter router) {
        this(candidates, router, () -> ModelRouter.BudgetSnapshot.unlimited());
    }

    /**
     * Route against a live budget view. The supplier is consulted on EVERY
     * call - the same router sees fresh snapshots as the ledger drains, so
     * mid-run budget exhaustion flips the next decision. Typical assembly
     * (D5, numbers not ledgers):
     * <pre>{@code
     * BudgetBook book = ...;
     * () -> {
     *     long limit = book.limitOf(BudgetDimension.USER, "alice");
     *     return limit < 0
     *             ? ModelRouter.BudgetSnapshot.unlimited()
     *             : ModelRouter.BudgetSnapshot.of(book.remainingOf(USER, "alice"), limit);
     * }
     * }</pre>
     *
     * @param candidates  modelId -&gt; client, at least one entry; keys are what
     *                    {@link RouteDecision#modelId()} addresses
     * @param router      the strategy deciding per call
     * @param budgetSource remaining-budget supplier, called per chat/stream
     */
    public RoutingModelClient(Map<String, ModelClient> candidates,
                              ModelRouter router,
                              Supplier<ModelRouter.BudgetSnapshot> budgetSource) {
        Objects.requireNonNull(candidates, "candidates");
        this.router = Objects.requireNonNull(router, "router");
        this.budgetSource = Objects.requireNonNull(budgetSource, "budgetSource");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("candidates must not be empty - routing needs at least one model to choose");
        }
        candidates.forEach((id, client) -> {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("candidate keys must not be null or blank");
            }
            Objects.requireNonNull(client, "candidate '" + id + "' client");
        });
        Map<String, ModelClient> copy = new LinkedHashMap<>();
        copy.putAll(candidates);
        this.candidates = Map.copyOf(copy);
    }

    /** The candidate keys this instance can resolve (for assembly-time sanity checks). */
    public List<String> candidateIds() {
        return List.copyOf(candidates.keySet());
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return select(request).chat(request);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        return select(request).stream(request);
    }

    // ============ Internals ============

    private ModelClient select(ModelRequest request) {
        RouteDecision decision = router.route(request, budgetSource.get());
        ModelClient selected = candidates.get(decision.modelId());
        if (selected == null) {
            // fail-loud at call time: a router returning unknown ids is a wiring bug
            throw new IllegalStateException("router selected unknown model '" + decision.modelId()
                    + "' (reason: " + decision.reason() + "); known candidates: " + candidates.keySet());
        }
        log.debug("routed to {} ({})", decision.modelId(), decision.reason());
        return selected;
    }
}

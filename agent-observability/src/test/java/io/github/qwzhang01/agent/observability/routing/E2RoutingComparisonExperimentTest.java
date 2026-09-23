package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2 comparison experiment: three configurations, one deterministic task set,
 * calibrated prices - the numbers that answer decision 25's question
 * "what does each routing signal cost, and what does it miss".
 * <p>
 * NOT a unit test of correctness (that lives in the sibling *Test classes) -
 * this is the experiment harness. It asserts only the INVARIANTS that must
 * hold regardless of prices; the cost table is printed for the notes, and its
 * qualitative shape (pre-route cheapest but blind, cascade pays double but
 * sees quality) is asserted structurally.
 * <p>
 * Simulation model (deterministic, no randomness):
 * <ul>
 *   <li>premium tier: always valid answers (JSON tasks get valid JSON)</li>
 *   <li>cheap tier: good at plain chat; fails JSON on a fixed task list
 *       (j2, j3, j5 - the simulator's known-bad list)</li>
 *   <li>prices calibrated to a 2026 gpt-4o-class spread per 1M tokens:
 *       premium $2.50 prompt / $10.00 completion; cheap $0.15 / $0.60</li>
 *   <li>tokens derived from message/response lengths / 4 (rough tokenizer)</li>
 * </ul>
 */
class E2RoutingComparisonExperimentTest {

    // calibrated prices (USD per 1M tokens)

    private static final double PREMIUM_PROMPT = 2.50;
    private static final double PREMIUM_COMPLETION = 10.00;
    private static final double CHEAP_PROMPT = 0.15;
    private static final double CHEAP_COMPLETION = 0.60;

    /** The cheap tier's known-bad list: these JSON tasks get broken JSON. */
    private static final java.util.Set<String> CHEAP_FAILS_JSON_ON =
            java.util.Set.of("j2", "j3", "j5");

    /**
     * Deterministic priced tier: computes token usage from request/response
     * lengths, records every call (tokens + task id) into a per-tier ledger.
     */
    static final class PricedTier implements ModelClient {
        final String name;
        final List<Call> ledger = new ArrayList<>();
        private final boolean premium;

        record Call(String taskId, int promptTokens, int completionTokens) {}

        PricedTier(String name, boolean premium) {
            this.name = name;
            this.premium = premium;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            String taskId = taskIdOf(request);
            int promptTokens = request.messages().stream()
                    .mapToInt(m -> Math.max(1, (m.content() == null ? 0 : m.content().length()) / 4))
                    .sum();
            String content = answerFor(taskId, request);
            int completionTokens = Math.max(1, content.length() / 4);
            ledger.add(new Call(taskId, promptTokens, completionTokens));
            return new ModelResponse(content, null, "stop",
                    new ModelResponse.TokenUsage(promptTokens, completionTokens,
                            promptTokens + completionTokens));
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = chat(request);
            return Stream.of(new StreamEvent.ContentDelta(r.content()), new StreamEvent.Done(r));
        }

        private String answerFor(String taskId, ModelRequest request) {
            boolean structured = request.responseFormat() != null;
            if (structured) {
                if (!premium && CHEAP_FAILS_JSON_ON.contains(taskId)) {
                    return "{\"answer\": 42";  // broken: unbalanced brace
                }
                return "{\"answer\": 42, \"unit\": \"tokens\"}";
            }
            return "Mock " + name + " answer to task " + taskId;
        }

        private static String taskIdOf(ModelRequest request) {
            for (int i = request.messages().size() - 1; i >= 0; i--) {
                ChatMessage m = request.messages().get(i);
                if (m.role() == io.github.qwzhang01.agent.core.model.ChatRole.USER
                        && m.content() != null && m.content().startsWith("[")) {
                    int close = m.content().indexOf(']');
                    if (close > 1) {
                        return m.content().substring(1, close);
                    }
                }
            }
            return "unknown";
        }
    }

    record Task(String id, ModelRequest request, boolean structured, boolean deepThread) {}

    private static List<Task> taskSet() {
        List<Task> tasks = new ArrayList<>();

        // 4 simple chat tasks (short threads, no marker) - cheap's home ground
        for (int i = 1; i <= 4; i++) {
            tasks.add(new Task("s" + i, ModelRequest.builder()
                    .model("m")
                    .addMessage(ChatMessage.user("[s" + i + "] hello, what's up?"))
                    .build(), false, false));
        }

        // 6 structured-output tasks (short threads) - cheap fails 3 of them (j2, j3, j5)
        for (int i = 1; i <= 6; i++) {
            tasks.add(new Task("j" + i, ModelRequest.builder()
                    .model("m")
                    .responseFormat(ModelRequest.ResponseFormat.json())
                    .addMessage(ChatMessage.user("[j" + i + "] extract entities as JSON"))
                    .build(), true, false));
        }

        // 4 deep-thread tasks (10 messages) - ComplexityRouter's premium trigger
        for (int i = 1; i <= 4; i++) {
            final int idx = i;
            ModelRequest.Builder b = ModelRequest.builder().model("m");
            IntStream.range(0, 9).forEach(k ->
                    b.addMessage(ChatMessage.user("[d" + idx + "] context turn " + k + " with some substance")));
            b.addMessage(ChatMessage.user("[d" + idx + "] now summarize everything carefully"));
            tasks.add(new Task("d" + idx, b.build(), false, true));
        }

        return tasks;
    }

    record ConfigResult(String name, double costUsd, int premiumCalls, int cheapCalls,
                        int defectsDelivered, int escalations) {}

    private static double costOf(PricedTier tier) {
        return tier.ledger.stream()
                .mapToDouble(c -> (tier.premium ? PREMIUM_PROMPT : CHEAP_PROMPT) * c.promptTokens() / 1_000_000
                        + (tier.premium ? PREMIUM_COMPLETION : CHEAP_COMPLETION) * c.completionTokens() / 1_000_000)
                .sum();
    }

    /** Evaluation-only gate pass: does the DELIVERED answer look defective? */
    private static boolean defective(ModelResponse delivered) {
        return !new RuleBasedQualityGate().judge(delivered).passed();
    }

    private static ConfigResult runConfig(String name, ModelClient config,
                                          PricedTier premiumLedger, PricedTier cheapLedger) {
        int premiumCallsBefore = premiumLedger.ledger.size();
        int cheapCallsBefore = cheapLedger.ledger.size();
        int defects = 0;
        for (Task t : taskSet()) {
            ModelResponse delivered = config.chat(t.request());
            if (defective(delivered)) {
                defects++;
            }
        }
        return new ConfigResult(name,
                costOf(premiumLedger) + costOf(cheapLedger),
                premiumLedger.ledger.size() - premiumCallsBefore,
                cheapLedger.ledger.size() - cheapCallsBefore,
                defects,
                0);
    }

    @Test
    @DisplayName("E2: three configurations on one task set - cost table + structural invariants")
    void comparison() {
        // A. all-premium baseline
        PricedTier premiumA = new PricedTier("premium", true);
        ConfigResult allPremium = runConfig("all-premium", premiumA, premiumA, new PricedTier("cheap", false));

        // B. pre-call complexity routing (RoutingModelClient + ComplexityRouter)
        PricedTier premiumB = new PricedTier("premium", true);
        PricedTier cheapB = new PricedTier("cheap", false);
        ModelClient preRoute = new RoutingModelClient(
                Map.of("premium", premiumB, "cheap", cheapB),
                new ComplexityRouter("premium", "cheap"));
        ConfigResult preRouteResult = runConfig("pre-route", preRoute, premiumB, cheapB);

        // C. cascade (cheap first, gate, escalate on failure)
        PricedTier premiumC = new PricedTier("premium", true);
        PricedTier cheapC = new PricedTier("cheap", false);
        ModelClient cascade = new CascadeModelClient(cheapC, premiumC, new RuleBasedQualityGate());
        int premiumBefore = premiumC.ledger.size();
        ConfigResult cascadeResult = runConfig("cascade", cascade, premiumC, cheapC);
        int escalations = premiumC.ledger.size() - premiumBefore;

        // print the table (goes into the experiment notes)

        System.out.println();
        System.out.println("=== E2 routing comparison (14 tasks, calibrated prices, deterministic sim) ===");
        System.out.printf("%-14s %10s %14s %12s %10s%n", "config", "cost($)", "premium calls", "cheap calls", "defects");
        print(allPremium);
        print(preRouteResult);
        print(cascadeResult);
        System.out.printf("%-14s escalations: %d (cheap gate failures re-issued on premium)%n",
                "cascade", escalations);
        System.out.println();

        // structural invariants (must hold regardless of prices)

        // 1. all-premium: zero defects, everything paid at premium price
        assertEquals(0, allPremium.defectsDelivered(), "premium tier never fails in this simulation");
        assertEquals(14, allPremium.premiumCalls());

        // 2. pre-route: cheapest possible policy, but BLIND to cheap's broken JSON
        assertEquals(3, preRouteResult.defectsDelivered(),
                "pre-call routing cannot see response quality: the 3 cheap-fail JSON tasks ship broken");
        assertEquals(3, preRouteResult.defectsDelivered());
        assertTrue(preRouteResult.costUsd() < allPremium.costUsd(),
                "routing simple+structured tasks to cheap must cost less than all-premium");
        assertEquals(4, preRouteResult.premiumCalls(), "only the 4 deep threads trigger the premium threshold");

        // 3. cascade: pays cheap+premium on the 3 failures, but zero defects delivered
        assertEquals(0, cascadeResult.defectsDelivered(), "the gate caught every broken JSON and escalated");
        assertEquals(3, escalations, "exactly the 3 cheap-fail tasks escalated");
        assertTrue(cascadeResult.costUsd() < allPremium.costUsd(),
                "11 cheap passes + 3 escalations must still cost less than 14 premium calls");

        // 4. the counterintuitive headline finding: cascade BEATS pre-route on cost here
        //    pre-route sends all 4 deep threads to premium sight-unseen (4 full premium
        //    calls); cascade tries cheap first on everything and only paid premium on the
        //    3 tasks with PROVABLE defects. Blind pre-commitment to premium is more
        //    expensive than paying for verification.
        assertTrue(cascadeResult.costUsd() < preRouteResult.costUsd(),
                "E2 headline: cascade (0.000409) beats pre-route (0.001226) - paying for "
                        + "quality verification is cheaper than blind pre-commitment on deep threads");
        assertTrue(preRouteResult.defectsDelivered() > cascadeResult.defectsDelivered(),
                "and cascade is also strictly safer: 0 defects vs pre-route's 3");
    }

    private static void print(ConfigResult r) {
        System.out.printf("%-14s %10.6f %14d %12d %10d%n",
                r.name(), r.costUsd(), r.premiumCalls(), r.cheapCalls(), r.defectsDelivered());
    }
}

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E3 comparison experiment: prompt-cache hit rate under four context-
 * management policies, priced with cache-aware pricing - the numbers that
 * answer decision 26's question "does prefix stability belong in the
 * ContextBuilder contract, and what does compaction cost the cache".
 * <p>
 * NOT a unit test of correctness (that lives in the sibling *Test classes) -
 * this is the experiment harness, same discipline as E2. It asserts only the
 * INVARIANTS that must hold regardless of tokenizer precision; the cost table
 * is printed for the notes.
 * <p>
 * Simulation model (deterministic, no randomness):
 * <ul>
 *   <li>10-turn conversation per scenario, same user inputs and same mock
 *       answers everywhere - only the CONTEXT POLICY differs</li>
 *   <li>A. baseline: append-only history, no compaction</li>
 *   <li>B. rewrite-in-place: at turn 5, ALL history is replaced by a summary
 *       (decision-9 style full rewrite)</li>
 *   <li>C. tail-only compaction: at turn 5, the oldest HALF of history is
 *       replaced by a summary; the stable head (turns' early prefix) survives</li>
 *   <li>D. E2 crossover: cascade escalation re-issues the ORIGINAL request on
 *       premium; cheap and premium are separate cache domains</li>
 *   <li>prices: Anthropic-style (read 0.1x, write 1.25x) on a $3/M base input
 *       and $15/M completion; both pricing styles printed</li>
 *   <li>tokens: message length / 4, same rough tokenizer as E2</li>
 * </ul>
 */
class E3CacheExperimentTest {

    // ============ calibrated prices (microUSD per 1M tokens) ============

    private static final long INPUT_MICROS = 3_000_000;      // $3.00/M
    private static final long COMPLETION_MICROS = 15_000_000; // $15.00/M

    private static final CachePricing ANTHROPIC_STYLE = CachePricing.anthropic(INPUT_MICROS);
    private static final CachePricing OPENAI_STYLE = CachePricing.openAiStyle(INPUT_MICROS);

    private static final String ANSWER_TEXT =
            "answer with enough length to be a plausible model answer for this turn";

    // ============ mock tier ============

    /**
     * Deterministic answering client: usage filled with the ROUGH tokenizer's
     * prompt count and a fixed answer length; the cache simulator's LCP adds
     * the cached portion.
     */
    static final class EchoClient implements ModelClient {
        @Override
        public ModelResponse chat(ModelRequest request) {
            int promptTokens = request.messages().stream()
                    .mapToInt(m -> Math.max(1, (m.content() == null ? 0 : m.content().length()) / 4))
                    .sum();
            String content = ANSWER_TEXT;
            int completionTokens = Math.max(1, content.length() / 4);
            return new ModelResponse(content, null, "stop",
                    new ModelResponse.TokenUsage(promptTokens, completionTokens,
                            promptTokens + completionTokens));
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = chat(request);
            return Stream.of(new StreamEvent.ContentDelta(r.content()), new StreamEvent.Done(r));
        }
    }

    /** Cheap tier that fails the gate on j2/j3/j5 (E2's known-bad list). */
    static final class SelectiveBadJsonClient implements ModelClient {
        private final java.util.Set<String> failsOn;

        SelectiveBadJsonClient(java.util.Set<String> failsOn) {
            this.failsOn = failsOn;
        }

        @Override
        public ModelResponse chat(ModelRequest request) {
            String taskId = taskIdOf(request);
            if (failsOn.contains(taskId)) {
                return new ModelResponse("{\"broken\": 42", null, "stop", null);
            }
            String content = "{\"answer\": 42, \"unit\": \"tokens\"}";
            return new ModelResponse(content, null, "stop", null);
        }

        @Override
        public Stream<StreamEvent> stream(ModelRequest request) {
            ModelResponse r = chat(request);
            return Stream.of(new StreamEvent.Done(r));
        }

        private static String taskIdOf(ModelRequest request) {
            for (int i = request.messages().size() - 1; i >= 0; i--) {
                ChatMessage m = request.messages().get(i);
                if (m.content() != null && m.content().startsWith("[")) {
                    int close = m.content().indexOf(']');
                    if (close > 1) {
                        return m.content().substring(1, close);
                    }
                }
            }
            return "unknown";
        }
    }

    // ============ conversation builder (per-policy contexts) ============

    /** One turn's user input + the "model answer" that lands in history. */
    private static final int TURNS = 10;
    private static final int COMPACT_AT = 5;

    private static String userTurn(int t) {
        return "user turn " + t + " asks a question about architecture with some substance to it";
    }

    private static String assistantTurn(int t) {
        return "assistant turn " + t + " explains the answer carefully with details and reasoning";
    }

    /** Build the message list a policy produces for turn t (history + new input). */
    private static List<ChatMessage> contextFor(Policy policy, int t) {
        List<ChatMessage> history = new ArrayList<>();
        for (int k = 1; k <= t - 1; k++) {
            history.add(ChatMessage.user(userTurn(k)));
            history.add(ChatMessage.assistant(assistantTurn(k)));
        }
        policy.apply(history, t);
        history.add(ChatMessage.user(userTurn(t)));
        return history;
    }

    /**
     * Context policies - the experiment's independent variable.
     * <p>
     * Design note: B and B2 share (nearly) the SAME prompt volumes - the only
     * difference is whether the rewritten prefix TEXT stays stable across
     * turns. That isolates the "prefix stability" variable from the "volume
     * reduction" variable, which otherwise confound each other (compression
     * shrinks prompts AND breaks prefixes at the same time).
     */
    enum Policy {
        BASELINE {
            @Override
            void apply(List<ChatMessage> history, int currentTurn) {
                // append-only: nothing to do
            }
        },
        REWRITE_FLAPPING {
            @Override
            void apply(List<ChatMessage> history, int currentTurn) {
                // decision-9 style: from COMPACT_AT on, ALL prior history is
                // replaced by a summary whose TEXT CHANGES EVERY TURN - the
                // prefix never survives, cache death on every call
                if (currentTurn > COMPACT_AT) {
                    history.clear();
                    history.add(ChatMessage.system("summary of turns 1.." + (currentTurn - 1)
                            + " condensed into one block that changes every turn"));
                }
            }
        },
        REWRITE_STABLE {
            @Override
            void apply(List<ChatMessage> history, int currentTurn) {
                // same rewrite-in-place shape and (nearly) the same volume as
                // REWRITE_FLAPPING, but the summary text is FROZEN at what it
                // was when compaction fired - the rewritten prefix is stable,
                // later turns append onto it and re-hit the cache
                if (currentTurn > COMPACT_AT) {
                    history.clear();
                    history.add(ChatMessage.system(FIXED_SUMMARY));
                }
            }
        },
        TAIL_ONLY {
            @Override
            void apply(List<ChatMessage> history, int currentTurn) {
                // keep the stable head; compress only the tail half
                if (currentTurn > COMPACT_AT) {
                    int keep = Math.max(1, history.size() / 2);
                    List<ChatMessage> head = new ArrayList<>(history.subList(0, keep));
                    history.clear();
                    history.addAll(head);
                    history.add(ChatMessage.system("summary of the compacted tail"));
                }
            }
        };

        abstract void apply(List<ChatMessage> history, int currentTurn);
    }

    /** The frozen summary REWRITE_STABLE reuses every turn (stable prefix). */
    private static final String FIXED_SUMMARY =
            "summary of turns 1..4 condensed into one block that stays frozen";

    // ============ measurement ============

    record ScenarioResult(String name, double costUsd, long promptTokens, long cachedReadTokens,
                          double hitRate, double noCacheCostUsd) {}

    private static ScenarioResult runScenario(String name, Policy policy, CachePricing pricing) {
        CacheSimulatingModelClient cacheClient = new CacheSimulatingModelClient(new EchoClient());

        for (int t = 1; t <= TURNS; t++) {
            List<ChatMessage> messages = contextFor(policy, t);
            ModelRequest request = ModelRequest.builder()
                    .model("m")
                    .messages(messages)
                    .build();
            cacheClient.chat(request);
        }

        long promptTotal = cacheClient.totalPromptTokens();
        long readTotal = cacheClient.totalCachedReadTokens();
        long writeTotal = cacheClient.totalCacheWriteTokens();
        // prompt cost: cached + written + base (disjoint parts, no double charge)
        long promptMicros = pricing.promptCostMicros((int) promptTotal, (int) readTotal, (int) writeTotal);
        long completionMicros = 0;
        // completion: every turn's answer priced at COMPLETION_MICROS
        // (tokens per answer are fixed in EchoClient; recompute here honestly)
        int answerTokens = Math.max(1, ANSWER_TEXT.length() / 4);
        completionMicros = (long) answerTokens * TURNS * COMPLETION_MICROS / 1_000_000;

        // parallel no-cache line: same volume, zero hit - what this policy
        // would cost on a provider WITHOUT caching. The gap between the two
        // lines IS the value the policy preserves/destroys.
        long noCachePromptMicros = (long) promptTotal * pricing.inputMicrosPerMillion() / 1_000_000;
        double noCacheCostUsd = (noCachePromptMicros + completionMicros) / 1e6;

        double costUsd = (promptMicros + completionMicros) / 1e6;
        return new ScenarioResult(name, costUsd, promptTotal, readTotal,
                cacheClient.hitRate(), noCacheCostUsd);
    }

    // ============ the experiment ============

    @Test
    @DisplayName("E3: context policies vs cache hit rate - cost table + structural invariants")
    void comparison() {
        // ---- scenarios A/B/B2/C under Anthropic-style pricing ----
        ScenarioResult baseline = runScenario("A.baseline", Policy.BASELINE, ANTHROPIC_STYLE);
        ScenarioResult flapping = runScenario("B.flapping", Policy.REWRITE_FLAPPING, ANTHROPIC_STYLE);
        ScenarioResult stable = runScenario("B2.stable", Policy.REWRITE_STABLE, ANTHROPIC_STYLE);
        ScenarioResult tail = runScenario("C.tail-only", Policy.TAIL_ONLY, ANTHROPIC_STYLE);

        // ---- scenario D: cascade crossover (E2's escalation under caching) ----
        CascadeD d = runCascadeScenario();

        // ---- print the table (goes into the notes) ----
        System.out.println();
        System.out.println("=== E3 cache comparison (10-turn conversation, deterministic sim) ===");
        System.out.println("prices: Anthropic-style on $3/M input, $15/M completion; "
                + "read " + ANTHROPIC_STYLE.readMultiplier() + "x, write " + ANTHROPIC_STYLE.writeMultiplier() + "x");
        System.out.printf("%-14s %10s %12s %14s %9s %11s%n",
                "scenario", "cost($)", "prompt tok", "cached read", "hit rate", "no-cache($)");
        print(baseline);
        print(flapping);
        print(stable);
        print(tail);
        System.out.println();
        System.out.printf("D.cascade: cheap domain hit %.4f (%d/%d), premium domain hit %.4f (%d/%d), "
                        + "escalated prompt tokens billed twice: %d%n",
                d.cheapHitRate, d.cheapCached, d.cheapPrompt,
                d.premiumHitRate, d.premiumCached, d.premiumPrompt,
                d.doubleBilledTokens);
        System.out.printf("D.cascade crossover: premium's re-issued prompt hits the same prefix "
                        + "in ITS OWN domain (first escalation LCP=%d); the clean re-issue adds "
                        + "no new prefix pollution (%s)%n",
                d.premiumCachedFirstEscalation,
                d.premiumCachedFirstEscalation == 0 ? "first escalation is a cold domain start"
                        : "prefix continuity observed");
        System.out.println();
        System.out.println("--- same scenarios under OpenAI-style implicit caching (writes free, reads 0.5x) ---");
        ScenarioResult baselineO = runScenario("A.baseline", Policy.BASELINE, OPENAI_STYLE);
        ScenarioResult flappingO = runScenario("B.flapping", Policy.REWRITE_FLAPPING, OPENAI_STYLE);
        ScenarioResult stableO = runScenario("B2.stable", Policy.REWRITE_STABLE, OPENAI_STYLE);
        ScenarioResult tailO = runScenario("C.tail-only", Policy.TAIL_ONLY, OPENAI_STYLE);
        print(baselineO);
        print(flappingO);
        print(stableO);
        print(tailO);
        System.out.println();

        // ---- structural invariants (must hold regardless of tokenizer precision) ----

        // 1. baseline: append-only history - hit rate grows with turns; every
        //    turn after the first has SOME hit; the newest turn is never cached
        assertTrue(baseline.cachedReadTokens > 0, "append-only history must cache-hit from turn 2");
        assertTrue(baseline.hitRate < 1.0, "the newest user turn is never cached (it is fresh input)");
        assertTrue(baseline.costUsd < baseline.noCacheCostUsd,
                "append-only history must beat its own no-cache line");

        // 2. flapping rewrite vs stable rewrite - the SAME volumes, the ONLY
        //    difference is prefix stability. This is the experiment's core
        //    isolation: cache value destroyed by instability alone.
        assertTrue(flapping.cachedReadTokens < stable.cachedReadTokens,
                "same-volume isolation: flapping summary must hit strictly less than frozen summary");
        assertTrue(flapping.costUsd > stable.costUsd,
                "same-volume isolation: flapping must cost MORE than stable: "
                        + flapping.costUsd + " vs " + stable.costUsd);
        assertTrue(flapping.noCacheCostUsd >= stable.noCacheCostUsd - 1e-12,
                "control check: the two policies bill (nearly) identical volumes no-cache");

        // 3. volume-reduction can dominate cache-loss (E3's counterintuitive
        //    finding vs the naive expectation): full rewrite shrinks the
        //    prompt so much that even a dying cache costs LESS than
        //    append-only. The cache damage is still real and measurable -
        //    against the policy's own no-cache line and against B2.
        assertTrue(flapping.costUsd < baseline.costUsd,
                "E3 finding: aggressive compaction + cache death still beats append-only on "
                        + "total cost: " + flapping.costUsd + " vs " + baseline.costUsd
                        + " - volume reduction dominates cache loss");
        double flappingLoss = flapping.noCacheCostUsd - flapping.costUsd;
        double stableGain = stable.noCacheCostUsd - stable.costUsd;
        assertTrue(stableGain > flappingLoss,
                "cache value preserved: stable rewrite saves " + stableGain
                        + " vs flapping's " + flappingLoss + " against their own no-cache lines");

        // 4. tail-only compaction: keeps most of the hit while still compacting
        assertTrue(tail.cachedReadTokens > 0, "head-preserving compaction still hits the cache");
        assertTrue(tail.cachedReadTokens < baseline.cachedReadTokens,
                "tail compaction still pays SOME cache loss (the compacted tail is a new prefix)");
        assertTrue(tail.hitRate > flapping.hitRate,
                "tail-only keeps a higher hit rate than flapping rewrite");

        // 5. cascade crossover: separate domains, honest double billing.
        //    The premium re-issue is a COLD START in premium's own domain (it
        //    has never seen this prompt) - LCP 0 on first escalation is the
        //    correct behavior, not pollution; and the re-issued prompt is
        //    IDENTICAL to the original, so it never drags cheap's failure
        //    text into premium's domain (no prefix pollution by construction).
        assertEquals(3, d.escalations, "the 3 JSON tasks escalate (same task set as E2)");
        assertTrue(d.cheapCachedFirstEscalation >= 0);
        assertEquals(0, d.premiumCachedFirstEscalation,
                "premium's first escalation is a cold domain start (LCP 0) - separate cache "
                        + "domains, no cross-domain pollution");
        assertEquals(3 * d.firstEscalationPrompt, d.doubleBilledTokens,
                "double billing is honest: 3 escalated prompts billed in both domains");

        // 6. pricing styles change the verdict - E3's second counterintuitive
        //    finding: under Anthropic-style explicit caching (write premium
        //    1.25x), a stable rewritten prefix pays for itself (stable <
        //    flapping). Under OpenAI-style implicit caching (writes FREE,
        //    reads 0.5x), the write premium does not exist, so the extra
        //    volume a stable summary carries is pure loss - flapping comes
        //    out AHEAD. Prefix-stability discipline is provider-priced, not
        //    universally free.
        assertTrue(stable.costUsd < flapping.costUsd,
                "Anthropic-style: stable rewrite beats flapping (write premium makes "
                        + "stability worth holding)");
        assertTrue(flappingO.costUsd < stableO.costUsd,
                "E3 finding: OpenAI-style (free writes) REVERSES the verdict - stability's "
                        + "extra volume is pure loss when writes are free: "
                        + flappingO.costUsd + " vs " + stableO.costUsd);
    }

    private static int sign(double v) {
        return v > 0 ? 1 : v < 0 ? -1 : 0;
    }

    private static void print(ScenarioResult r) {
        System.out.printf("%-14s %10.6f %12d %14d %9.4f %11.6f%n",
                r.name(), r.costUsd(), r.promptTokens(), r.cachedReadTokens(), r.hitRate(),
                r.noCacheCostUsd());
    }

    // ============ scenario D internals ============

    record CascadeD(double cheapHitRate, long cheapCached, long cheapPrompt,
                    double premiumHitRate, long premiumCached, long premiumPrompt,
                    int escalations, int firstEscalationPrompt,
                    int cheapCachedFirstEscalation, int premiumCachedFirstEscalation,
                    long doubleBilledTokens) {}

    /**
     * Scenario D: E2's cascade (cheap fails JSON on j2/j3/j5 -> premium
     * re-issues the original request) observed under cache simulation.
     * Cheap and premium are separate cache domains (different model ids),
     * mirroring real provider cache partitioning.
     */
    private static CascadeD runCascadeScenario() {
        CacheSimulatingModelClient cheapWithCache = new CacheSimulatingModelClient(
                new SelectiveBadJsonClient(java.util.Set.of("j2", "j3", "j5")));
        CacheSimulatingModelClient premiumWithCache = new CacheSimulatingModelClient(
                new EchoClient());
        // wire the tiers directly: the cascade's cheap tier IS the
        // cache-simulated bad-JSON client; premium likewise (each tier gets
        // its own cache domain keyed by its model id)
        ModelClient cascade = new CascadeModelClient(cheapWithCache, premiumWithCache,
                new RuleBasedQualityGate());

        java.util.Set<String> failsOn = java.util.Set.of("j2", "j3", "j5");
        int escalations = 0;
        int firstEscalationPrompt = 0;
        int cheapCachedFirstEscalation = 0;
        int premiumCachedFirstEscalation = 0;
        long doubleBilled = 0;

        for (int i = 1; i <= 6; i++) {
            String id = "j" + i;
            ModelRequest request = ModelRequest.builder()
                    .model("cheap")
                    .responseFormat(ModelRequest.ResponseFormat.json())
                    .addMessage(ChatMessage.user("[" + id + "] extract entities as JSON with some context"))
                    .build();
            long cheapPromptBefore = cheapWithCache.totalPromptTokens();
            long cheapCachedBefore = cheapWithCache.totalCachedReadTokens();
            long premiumPromptBefore = premiumWithCache.totalPromptTokens();
            long premiumCachedBefore = premiumWithCache.totalCachedReadTokens();

            ModelResponse delivered = cascade.chat(request);

            boolean escalated = premiumWithCache.totalPromptTokens() > premiumPromptBefore;
            if (escalated) {
                escalations++;
                // the escalated prompt is billed in BOTH domains (simulator
                // accounting); the cheap-side delta IS its billed size
                doubleBilled += cheapWithCache.totalPromptTokens() - cheapPromptBefore;
                if (escalations == 1) {
                    firstEscalationPrompt = (int) (cheapWithCache.totalPromptTokens() - cheapPromptBefore);
                    cheapCachedFirstEscalation = (int) (cheapWithCache.totalCachedReadTokens() - cheapCachedBefore);
                    premiumCachedFirstEscalation = (int) (premiumWithCache.totalCachedReadTokens() - premiumCachedBefore);
                }
            }
            assertNotNull(delivered);
        }

        long cheapPrompt = cheapWithCache.totalPromptTokens();
        long cheapCached = cheapWithCache.totalCachedReadTokens();
        long premiumPrompt = premiumWithCache.totalPromptTokens();
        long premiumCached = premiumWithCache.totalCachedReadTokens();

        return new CascadeD(
                cheapPrompt == 0 ? 0 : (double) cheapCached / cheapPrompt, cheapCached, cheapPrompt,
                premiumPrompt == 0 ? 0 : (double) premiumCached / premiumPrompt, premiumCached, premiumPrompt,
                escalations, firstEscalationPrompt, cheapCachedFirstEscalation,
                premiumCachedFirstEscalation, doubleBilled);
    }

    private static int promptTokensOf(String taskId) {
        return Math.max(1, ("[" + taskId + "] extract entities as JSON with some context").length() / 4);
    }
}

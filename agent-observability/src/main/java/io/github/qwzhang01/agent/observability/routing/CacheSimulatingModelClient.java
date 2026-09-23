package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ChatMessage;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Cache-simulating ModelClient decorator (E3): observes requests in order,
 * computes the longest-common-prefix (LCP) against the previous request per
 * cache domain, and reports the hit as {@code cachedTokens} in the returned
 * usage - making prompt-cache hit rate VISIBLE and BILLABLE (decision 26).
 * <p>
 * WHY a decorator and not a router strategy: the cache hit rate is a property
 * of the REQUEST SEQUENCE as seen at the model boundary. Only a decorator in
 * the ModelClient chain observes that sequence in order; the loop, the
 * ContextBuilder and the routers each see only fragments of it. Same layer
 * discipline as Routing/Cascade (decision 25): the signal lives where the
 * data flows.
 * <p>
 * Cache domain: providers cache per (model, conversation). Two keys map to
 * the same provider cache when they share the same model id - requests with
 * DIFFERENT model ids never share a prefix (they hit different weights/sessions).
 * This decorator keys its LCP memory on {@code request.model()}; when the
 * loop swaps configs (handoff) or a cascade escalates cheap -> premium, the
 * model id changes and the domains stay separate - exactly mirroring how
 * real providers partition their caches. A separate "session id" axis is
 * deliberately NOT modeled: providers do not expose it and the loop does not
 * carry one; over-modeling would fake precision.
 * <p>
 * Hit accounting: the LCP portion of the prompt is the cache HIT (read at a
 * discount); the remaining suffix is freshly processed. For explicit-cache
 * providers (Anthropic) the suffix is also a WRITE premium event; for
 * implicit-cache providers (OpenAI) writes are free. The harness prices both
 * variants via {@link CachePricing}; this decorator only reports tokens,
 * never money.
 * <p>
 * Tokenization: text length / 4, the same rough tokenizer as the E2 harness -
 * deterministic, no external dependency. Structural conclusions (which
 * scenario hits, which misses) are exact; absolute token counts are
 * orientation-scale.
 * <p>
 * Determinism: zero randomness; the same request sequence produces the same
 * hit accounting every run. The E3 harness asserts invariants that hold
 * regardless of tokenizer precision.
 * <p>
 * Recommended wiring: wrap the TIER client (inside routing, so every tier's
 * cache domain is tracked separately):
 * {@code Observing(CacheSim(Routing(Map.of(cheap, CacheSim(cheapClient), premium, CacheSim(premiumClient)))))}
 */
public final class CacheSimulatingModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(CacheSimulatingModelClient.class);

    private final ModelClient delegate;
    private final CachePolicy policy;

    /** Last-seen prefix per cache domain (model id -> last request's token sequence). */
    private final Map<String, List<String>> lastPromptByModel = new ConcurrentHashMap<>();

    /**
     * How the simulator maps LCP hits to billed tokens. v1 default:
     * {@link HitPolicy#LCP_TO_CACHED} - the exact prefix-match semantics of
     * OpenAI implicit caching.
     */
    public enum HitPolicy {
        /** The LCP portion is billed as cache-read; suffix as uncached input. */
        LCP_TO_CACHED
    }

    /**
     * Pluggable cache behavior for future variants (explicit breakpoint
     * caching a la Anthropic's cache_control). v1 ships only LCP_TO_CACHED.
     */
    public interface CachePolicy {
        HitPolicy hitPolicy();
    }

    /** Default policy: OpenAI-style implicit prefix matching. */
    public CacheSimulatingModelClient(ModelClient delegate) {
        this(delegate, () -> HitPolicy.LCP_TO_CACHED);
    }

    public CacheSimulatingModelClient(ModelClient delegate, CachePolicy policy) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        CacheAccounting accounting = account(request);

        ModelResponse response = delegate.chat(request);
        return withCacheUsage(response, accounting);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        CacheAccounting accounting = account(request);

        // Same discipline as CascadeModelClient: streams are consumed to Done
        // before usage can be attached - but here there is no gate decision to
        // make, so we can pass through and rewrite only the final Done event.
        java.util.List<StreamEvent> events = new ArrayList<>();
        ModelResponse finalResponse = null;
        try (Stream<StreamEvent> s = delegate.stream(request)) {
            for (StreamEvent e : s.toList()) {
                events.add(e);
                if (e instanceof StreamEvent.Done done) {
                    finalResponse = done.finalResponse();
                }
            }
        }
        if (finalResponse == null) {
            // contract violation by delegate - no Done to rewrite; pass events
            // through unchanged (the caller's loop handles malformed streams)
            return events.stream();
        }
        ModelResponse rewritten = withCacheUsage(finalResponse, accounting);
        List<StreamEvent> rewrittenEvents = new ArrayList<>(events.size());
        for (StreamEvent e : events) {
            if (e instanceof StreamEvent.Done) {
                rewrittenEvents.add(new StreamEvent.Done(rewritten));
            } else {
                rewrittenEvents.add(e);
            }
        }
        return rewrittenEvents.stream();
    }

    // Queries (for the harness / notes)

    /** Total cache-read tokens observed so far, across all domains. */
    public long totalCachedReadTokens() {
        return statsCacheReadTotal;
    }

    /** Total freshly-written tokens observed so far (cache inserts). */
    public long totalCacheWriteTokens() {
        return statsCacheWriteTotal;
    }

    /** Total billed prompt tokens observed so far. */
    public long totalPromptTokens() {
        return statsPromptTotal;
    }

    /** Observed hit rate: cachedRead / billedPrompt across all calls. */
    public double hitRate() {
        return statsPromptTotal == 0 ? 0.0 : (double) statsCacheReadTotal / statsPromptTotal;
    }

    private long statsCacheReadTotal;
    private long statsCacheWriteTotal;
    private long statsPromptTotal;

    /**
     * Account one request against its cache domain: compute the LCP with the
     * previous prompt, record the new prompt as the domain's baseline.
     */
    private synchronized CacheAccounting account(ModelRequest request) {
        String domain = request.model() == null ? "" : request.model();
        List<String> promptTokens = tokenize(request);
        List<String> last = lastPromptByModel.get(domain);

        int lcp = 0;
        if (last != null) {
            int max = Math.min(last.size(), promptTokens.size());
            while (lcp < max && last.get(lcp).equals(promptTokens.get(lcp))) {
                lcp++;
            }
        }

        // OpenAI-style implicit cache: hit portion reads at a discount, the
        // suffix is fresh input. Write accounting: the suffix tokens are what
        // this call inserts into the cache (for explicit-cache pricing).
        int billedPrompt = promptTokens.size();
        int cachedRead = lcp;
        int cacheWrite = promptTokens.size() - lcp;

        lastPromptByModel.put(domain, promptTokens);
        statsCacheReadTotal += cachedRead;
        statsCacheWriteTotal += cacheWrite;
        statsPromptTotal += billedPrompt;

        return new CacheAccounting(billedPrompt, cachedRead, cacheWrite);
    }

    /**
     * Tokenize the prompt for prefix comparison: the parts of a request that
     * a provider would treat as the cacheable prefix, in order. v1 models
     * system prompt + messages; tool schemas are counted in the billing
     * totals (see harness) but not as prefix-comparable tokens (they are
     * provider-internal and stable per config, so their absence only shifts
     * absolute numbers, not structural conclusions).
     */
    private static List<String> tokenize(ModelRequest request) {
        List<String> tokens = new ArrayList<>();
        for (ChatMessage m : request.messages()) {
            // role marker: prefix matching includes message boundaries
            tokens.add("role:" + m.role());
            if (m.content() != null) {
                for (int i = 0; i < m.content().length(); i += 4) {
                    tokens.add(m.content().substring(i, Math.min(m.content().length(), i + 4)));
                }
            }
            if (m.toolCallId() != null) {
                tokens.add("tcid:" + m.toolCallId());
            }
            if (m.toolCalls() != null) {
                for (var tc : m.toolCalls()) {
                    tokens.add("tc:" + tc.id() + ":" + tc.name() + ":" + tc.arguments());
                }
    }
        }
        return tokens;
    }

    private static ModelResponse withCacheUsage(ModelResponse response, CacheAccounting acc) {
        ModelResponse.TokenUsage existing = response.usage();
        int completion = existing == null ? 0 : existing.completionTokens();
        int reportedCached = existing == null ? 0 : existing.cachedTokens();
        // Provider-reported cachedTokens win over the simulator's LCP estimate
        // when present (the real provider knows its own cache); the simulator
        // fills in for mocks and local tests.
        int cached = Math.max(reportedCached, acc.cachedRead());
        int prompt = Math.max(existing == null ? 0 : existing.promptTokens(), acc.billedPrompt());
        return new ModelResponse(response.content(), response.toolCalls(),
                response.finishReason(),
                new ModelResponse.TokenUsage(prompt, completion,
                        prompt + completion, cached));
    }

    /** One call's cache accounting: what the provider would bill for the prompt. */
    record CacheAccounting(int billedPrompt, int cachedRead, int cacheWrite) {
    }
}

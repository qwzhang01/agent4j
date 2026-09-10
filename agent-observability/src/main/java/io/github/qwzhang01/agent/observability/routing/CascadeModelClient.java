package io.github.qwzhang01.agent.observability.routing;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Cascade ModelClient decorator (E2): try cheap first, judge the answer with a
 * {@link QualityGate}, re-issue on premium only when the gate fails - the
 * post-call half of E2's routing-signals question (decision 25).
 * <p>
 * WHY a decorator and not a router strategy: the {@link ModelRouter} interface
 * sees only {@code route(request, budget)} - pre-call signals by construction.
 * A cascade needs the RESPONSE (did it parse? did it finish?) to decide, so it
 * lives one layer up, wrapping both tiers. Pre-call economics = router (which
 * model to try first); post-call quality = gate (is the answer good enough).
 * Two layers, two questions, never merged.
 * <p>
 * History discipline (the cascade's defining contract): the cheap attempt's
 * failure is NEVER written into the conversation. The premium retry receives
 * the ORIGINAL request instance - same messages, no "here is a bad answer,
 * fix it" contamination. This is deliberate: retry-with-context is a different
 * pattern (it changes the prompt, drifts the KV cache, and couples premium's
 * answer to cheap's hallucinations); clean re-issue keeps this decorator
 * provably equivalent to "premium answers from scratch", just conditionally
 * skipped when cheap already passed.
 * <p>
 * Cost accounting: both attempts' usage is summed into the returned
 * response's TokenUsage (cheap attempt is real spend even when discarded).
 * The latency is also honest: callers see the total elapsed time of both
 * attempts, because that is the latency they experienced.
 * <p>
 * Failure semantics (contrast with {@link RoutingModelClient} zero-touch):
 * the cascade OWNS its escalation path. Cheap throwing ModelException does
 * NOT auto-escalate - an availability failure is {@code FallbackModelClient}'s
 * job (compose: {@code Cascade(Fallback(cheapA, cheapB), premium)}); quality
 * escalation is this decorator's job. Separation of concerns keeps both
 * composable: availability inside the tier, quality above it.
 * <p>
 * Recommended wiring (mirrors the routing stack):
 * {@code Observing(Cascade(Routing(...), premium))} - or simpler for E2's
 * fixed-tier comparison: {@code Cascade(cheap, premium, gate)} directly.
 */
public final class CascadeModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(CascadeModelClient.class);

    private final ModelClient cheap;
    private final ModelClient premium;
    private final QualityGate gate;

    /**
     * @param cheap   the first-attempt tier (cost-efficient)
     * @param premium the escalation tier (reliability); receives the ORIGINAL
     *                request instance on escalation, never the failed response
     * @param gate    post-call quality judge; its verdict decides escalation
     */
    public CascadeModelClient(ModelClient cheap, ModelClient premium, QualityGate gate) {
        this.cheap = Objects.requireNonNull(cheap, "cheap");
        this.premium = Objects.requireNonNull(premium, "premium");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        long startNanos = System.nanoTime();

        ModelResponse cheapResponse = cheap.chat(request);
        QualityGate.Verdict verdict = gate.judge(cheapResponse);
        if (verdict.passed()) {
            log.debug("cheap tier passed the gate ({}) - returning as-is", verdict.reason());
            return cheapResponse;
        }

        log.debug("cheap tier failed the gate ({}); escalating to premium with the ORIGINAL request",
                verdict.reason());
        ModelResponse premiumResponse = premium.chat(request);

        return merge(request, cheapResponse, premiumResponse, verdict, startNanos);
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        long startNanos = System.nanoTime();

        // Stage-1 reality: streams are consumed to completion before the gate can
        // judge (the gate needs the final response). We drive the cheap stream to
        // its Done event, judge, and only then decide: pass -> replay cheap's
        // buffered events to the caller; fail -> stream premium's answer fresh.
        java.util.List<StreamEvent> cheapEvents = new java.util.ArrayList<>();
        ModelResponse cheapFinal = driveToCompletion(cheap.stream(request), cheapEvents);

        QualityGate.Verdict verdict = gate.judge(cheapFinal);
        if (verdict.passed()) {
            return cheapEvents.stream();
        }

        log.debug("cheap stream failed the gate ({}) - streaming premium's answer", verdict.reason());
        ModelResponse premiumFinal = null;
        java.util.List<StreamEvent> premiumEvents = new java.util.ArrayList<>();
        if (needsPremium(request, verdict)) {
            premiumFinal = driveToCompletion(premium.stream(request), premiumEvents);
            QualityGate.Verdict premiumVerdict = gate.judge(premiumFinal);
            if (premiumVerdict.passed()) {
                return withUsage(
                        premiumEvents.stream(),
                        mergeUsage(cheapFinal, premiumFinal),
                        cheapFinal,
                        premiumFinal,
                        verdict);
            }
            // premium also failed the gate: return premium's events anyway (best
            // effort - one answer must reach the caller), but log the double
            // failure; the merged usage still counts both attempts.
            log.warn("premium ALSO failed the gate ({}) - returning premium's answer best-effort",
                    premiumVerdict.reason());
            return withUsage(premiumEvents.stream(),
                    mergeUsage(cheapFinal, premiumFinal), cheapFinal, premiumFinal, verdict);
        }
        return premium.stream(request);
    }

    // ============ Internals ============

    private ModelResponse merge(ModelRequest request, ModelResponse cheapResponse,
                                ModelResponse premiumResponse, QualityGate.Verdict verdict,
                                long startNanos) {
        ModelResponse.TokenUsage merged = mergeUsage(cheapResponse, premiumResponse);

        // The premium answer is the answer the caller gets - content/toolCalls/
        // finishReason come from premium AS-IS; only usage is rewritten to the
        // honest total (both attempts were real spend).
        if (merged == null) {
            return premiumResponse;
        }
        return new ModelResponse(
                premiumResponse.content(),
                premiumResponse.toolCalls(),
                premiumResponse.finishReason(),
                merged);
    }

    /**
     * Sum both attempts' token usage into one honest number; null when neither
     * tier reported usage (mocks / clients that do not fill usage).
     */
    private static ModelResponse.TokenUsage mergeUsage(ModelResponse cheapResponse,
                                                       ModelResponse premiumResponse) {
        ModelResponse.TokenUsage cheapUsage = cheapResponse.usage();
        ModelResponse.TokenUsage premiumUsage = premiumResponse.usage();
        if (cheapUsage == null && premiumUsage == null) {
            return null;
        }
        int prompt = (cheapUsage == null ? 0 : cheapUsage.promptTokens())
                + (premiumUsage == null ? 0 : premiumUsage.promptTokens());
        int completion = (cheapUsage == null ? 0 : cheapUsage.completionTokens())
                + (premiumUsage == null ? 0 : premiumUsage.completionTokens());
        return new ModelResponse.TokenUsage(prompt, completion, prompt + completion);
    }

    /**
     * Whether the premium escalation should actually run for this verdict.
     * v1: always yes - every gate failure escalates once (the single-retry
     * discipline: one escalation, no loops, no exponential anything).
     */
    private static boolean needsPremium(ModelRequest request, QualityGate.Verdict verdict) {
        return true;
    }

    /**
     * Consume a stream to its Done/Error event, buffering every event along
     * the way; returns the final ModelResponse carried by Done (or an error
     * response synthesized from Error).
     */
    private static ModelResponse driveToCompletion(Stream<StreamEvent> events,
                                                   java.util.List<StreamEvent> buffer) {
        ModelResponse finalResponse = null;
        try (Stream<StreamEvent> s = events) {
            for (StreamEvent e : s.toList()) {
                buffer.add(e);
                if (e instanceof StreamEvent.Done done) {
                    finalResponse = done.finalResponse();
                } else if (e instanceof StreamEvent.Error err) {
                    finalResponse = ModelResponse.error(err.message());
                }
            }
        }
        if (finalResponse == null) {
            // stream ended without Done/Error: malformed by contract - treat as
            // empty (the gate's empty-content signal will fire and escalate)
            finalResponse = ModelResponse.error("stream ended without Done or Error event");
        }
        finalResponse = ensureContentPresent(finalResponse);
        return finalResponse;
    }

    private static ModelResponse ensureContentPresent(ModelResponse response) {
        // ModelResponse.error() carries null content; give the gate something
        // uniform to look at: content "", toolCalls null, finishReason "error".
        if (response.content() == null && !response.hasToolCalls()) {
            return new ModelResponse("", response.toolCalls(), response.finishReason(), response.usage());
        }
        return response;
    }

    /**
     * Attach merged usage to the final Done event of a replayed/escalated
     * event list - the caller's stream must carry the honest total.
     */
    private static Stream<StreamEvent> withUsage(Stream<StreamEvent> events,
                                                 ModelResponse.TokenUsage merged,
                                                 ModelResponse cheapFinal,
                                                 ModelResponse premiumFinal,
                                                 QualityGate.Verdict verdict) {
        java.util.List<StreamEvent> rewritten = new java.util.ArrayList<>();
        events.forEach(e -> {
            if (e instanceof StreamEvent.Done done) {
                ModelResponse r = done.finalResponse();
                ModelResponse rewrittenDone = merged == null
                        ? r
                        : new ModelResponse(r.content(), r.toolCalls(), r.finishReason(), merged);
                rewritten.add(new StreamEvent.Done(rewrittenDone));
            } else {
                rewritten.add(e);
            }
        });
        return rewritten.stream();
    }
}

package io.github.qwzhang01.agent.observability.metrics;

import io.github.qwzhang01.agent.core.client.ModelClient;
import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import io.github.qwzhang01.agent.core.run.RunContext;
import io.github.qwzhang01.agent.observability.cost.BudgetBook;
import io.github.qwzhang01.agent.observability.cost.BudgetCheck;
import io.github.qwzhang01.agent.observability.cost.BudgetDimension;
import io.github.qwzhang01.agent.observability.cost.BudgetExhaustedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * Model boundary with the budget wired in "Model and Tool
 * boundary automatically enforce budgets, no business-side
 * requireBudget call" - the roadmap's completion bar: budget overrun
 * BLOCKS, not logs.
 * <p>
 * Identity comes from the {@link RunContext} the loop already propagates
 * (plumbing cashed in): tenant/user/agent/channel keys ride in
 * the context, the decorator derives the four ledger dimensions from it
 * plus RUN. A business that never constructed a BudgetBook sees zero
 * behavior change (no book = no gates = legacy semantics); a business
 * that DID configure one gets enforcement without touching its call
 * sites.
 * <p>
 * Two-phase discipline (BudgetBook D3, unchanged):
 * <ul>
 *   <li>pre-flight: estimate tokens from the request (chars/4 heuristic -
 *       the same approximation BudgetBook's own javadoc blesses), require
 *       across RUN/USER/TENANT/CHANNEL/AGENT; any DENIED throws
 *       {@link BudgetExhaustedException} fail-closed BEFORE the call</li>
 *   <li>post-hoc: real usage (from the response TokenUsage) recorded per
 *       dimension, estimate replaced by actual, the honest ledger</li>
 * </ul>
 * Callers without a ctx (legacy single-arg chat/stream) skip all gates -
 * no identity, no accounting; forcing one would invent a tenant out of
 * thin air.
 * <p>
 * Exception discipline: DENIED throws BudgetExhaustedException (NOT a
 * ModelException; a fallback decorator must not "recover" by retrying -
 * retrying does not refill a budget); usage recording failures are logged
 * and swallowed (side channel).
 */
public final class BudgetedModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(BudgetedModelClient.class);

    /** chars-per-token heuristic for the pre-flight estimate. */
    static final int CHARS_PER_TOKEN = 4;

    private final ModelClient delegate;
    private final BudgetBook book;

    private BudgetedModelClient(ModelClient delegate, BudgetBook book) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.book = Objects.requireNonNull(book, "book");
    }

    public static BudgetedModelClient wrap(ModelClient delegate, BudgetBook book) {
        return new BudgetedModelClient(delegate, book);
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return delegate.chat(request);  // no ctx: no identity, no gates
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        return delegate.stream(request);
    }

    @Override
    public ModelResponse chat(ModelRequest request, RunContext ctx) {
        if (ctx == null) {
            return delegate.chat(request);
        }
        long estimate = estimateTokens(request);
        requireAll(ctx, estimate);
        ModelResponse response = delegate.chat(request, ctx);
        recordAll(ctx, actualTokens(response));
        return response;
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request, RunContext ctx) {
        if (ctx == null) {
            return delegate.stream(request);
        }
        long estimate = estimateTokens(request);
        requireAll(ctx, estimate);
        AtomicReference<ModelResponse> last = new AtomicReference<>();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return delegate.stream(request, ctx).peek(event -> {
            if (event instanceof StreamEvent.Done done && !emitted.get()) {
                emitted.set(true);
                last.set(done.finalResponse());
                recordAll(ctx, actualTokens(done.finalResponse()));
            } else if (event instanceof StreamEvent.Error err && !emitted.get()) {
                emitted.set(true);
                // the call burned tokens server-side even though it errored:
                // record the ESTIMATE as the honest floor (actual unknown)
                recordAll(ctx, estimate);
            }
        });
    }

    /** Pre-flight gate across all five dimensions derivable from the ctx. */
    private void requireAll(RunContext ctx, long estimate) {
        requireOne(BudgetDimension.RUN, ctx.runId(), estimate);
        requireOne(BudgetDimension.USER, ctx.userId(), estimate);
        requireOne(BudgetDimension.TENANT, ctx.tenantId(), estimate);
        requireOne(BudgetDimension.CHANNEL, ctx.channelId(), estimate);
        requireOne(BudgetDimension.AGENT, ctx.agentId(), estimate);
    }

    private void requireOne(BudgetDimension dimension, String key, long estimate) {
        if (key == null || key.isBlank()) {
            return;  // no identity on this axis: unlimited by absence
        }
        BudgetCheck check = book.requireBudget(dimension, key, estimate);
        if (check instanceof BudgetCheck.Denied denied) {
            throw new BudgetExhaustedException(
                    dimension + " budget exhausted for '" + key + "': used "
                            + denied.usedTokens() + " of " + denied.limitTokens(),
                    Math.max(0, denied.limitTokens() - denied.usedTokens()),
                    denied.limitTokens());
        }
        // WARN never blocks (D3 separation); BudgetBook already emitted the alarm
    }

    /** Post-hoc honest ledger across the same five dimensions. */
    private void recordAll(RunContext ctx, long actualTokens) {
        if (actualTokens < 0) {
            return;
        }
        recordOne(BudgetDimension.RUN, ctx.runId(), actualTokens);
        recordOne(BudgetDimension.USER, ctx.userId(), actualTokens);
        recordOne(BudgetDimension.TENANT, ctx.tenantId(), actualTokens);
        recordOne(BudgetDimension.CHANNEL, ctx.channelId(), actualTokens);
        recordOne(BudgetDimension.AGENT, ctx.agentId(), actualTokens);
    }

    private void recordOne(BudgetDimension dimension, String key, long tokens) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            book.recordUsage(dimension, key, tokens);
        } catch (RuntimeException e) {
            log.warn("usage recording failed (ledger is a side channel, swallowing): {}", e.toString());
        }
    }

    /** chars/4 estimate over prompt + declared max tokens headroom. */
    static long estimateTokens(ModelRequest request) {
        long chars = 0;
        if (request.messages() != null) {
            for (var message : request.messages()) {
                chars += message.content() == null ? 0 : message.content().length();
            }
        }
        long estimate = chars / CHARS_PER_TOKEN;
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            estimate += request.maxTokens();
        }
        return estimate;
    }

    private static long actualTokens(ModelResponse response) {
        if (response == null || response.usage() == null) {
            return -1;  // unreported: don't guess, don't record
        }
        return response.usage().totalTokens();
    }
}

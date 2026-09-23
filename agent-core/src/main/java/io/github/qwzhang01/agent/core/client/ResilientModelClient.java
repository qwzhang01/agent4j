package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Stage 6.1: provider resilience knobs the roadmap names — Retry-After
 * honoring, circuit breaking, credential rotation — as ONE decorator so a
 * multi-provider setup gets all three without stacking three wrappers.
 * <p>
 * <b>Retry-After.</b> When a failing call surfaces a
 * {@link ProviderCallException} whose {@code getRetryAfter()} is present,
 * the next attempt waits THAT long (capped) instead of the exponential
 * guess. Providers tell you when to come back; believing them beats
 * backoff arithmetic.
 * <p>
 * <b>Circuit breaker.</b> After {@code failureThreshold} consecutive
 * failures the breaker OPENs: calls fail fast with
 * {@code ProviderCallException(MODEL_ERROR, "circuit open")} without
 * touching the provider, until {@code openDuration} passes, then one probe
 * call (HALF_OPEN). Success closes it again. This is per-decorator, not
 * per-provider-process: each {@code ResilientModelClient} guards exactly
 * the delegate beneath it.
 * <p>
 * <b>Credential rotation.</b> On AUTH_ERROR the decorator asks the
 * {@link CredentialRotation} supplier for the next credential and retries
 * with the rotated value — the response of "key expired mid-flight" is a
 * new key, not a dead run. When the supplier is absent (null) or
 * exhausted, AUTH_ERROR propagates untouched (legacy behaviour).
 * <p>
 * Legacy compatibility: with rotation absent and thresholds at their
 * defaults this behaves as {@link RetryModelClient} plus Retry-After
 * honoring — existing catch sites see the same {@link ModelException}
 * family, nothing else changes.
 */
public class ResilientModelClient implements ModelClient {

    /** Breaker states. CLOSED = normal, OPEN = fail-fast, HALF_OPEN = probing. */
    enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    private final ModelClient delegate;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final Duration retryAfterCap;
    private final CredentialRotation credentialRotation;
    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<BreakerState> breakerState =
            new AtomicReference<>(BreakerState.CLOSED);
    private volatile long openedAtMillis = -1;

    /**
     * Full form. See class doc for semantics of each knob.
     *
     * @param delegate            the wrapped client (required)
     * @param maxRetries          retries beyond the first attempt (0 = no retry)
     * @param initialBackoff      exponential-backoff base for retryable errors
     * @param retryAfterCap       upper bound honoring provider Retry-After (null = honor fully)
     * @param credentialRotation  supplies rotated credentials on AUTH_ERROR (null = off)
     * @param failureThreshold    consecutive failures before the breaker opens
     * @param openDuration        how long the breaker stays open before probing
     */
    public ResilientModelClient(ModelClient delegate,
                                int maxRetries,
                                Duration initialBackoff,
                                Duration retryAfterCap,
                                CredentialRotation credentialRotation,
                                int failureThreshold,
                                Duration openDuration) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.maxRetries = Math.max(0, maxRetries);
        this.initialBackoff = initialBackoff != null ? initialBackoff : Duration.ofMillis(500);
        this.retryAfterCap = retryAfterCap;
        this.credentialRotation = credentialRotation;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration != null ? openDuration : Duration.ofSeconds(30);
    }

    /** Retry + Retry-After only (no rotation, no breaker). */
    public ResilientModelClient(ModelClient delegate, int maxRetries, Duration initialBackoff) {
        this(delegate, maxRetries, initialBackoff, null, null, Integer.MAX_VALUE, Duration.ZERO);
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        return callWithResilience("chat", () -> delegate.chat(request));
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // Stream open participates in the breaker; stream BODY failures are
        // the stream's own Error events (same as RetryModelClient's stance).
        return callWithResilience("stream", () -> delegate.stream(request));
    }

    private <T> T callWithResilience(String op, Supplier<T> call) {
        checkBreakerBeforeCall(op);

        ProviderCallException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = call.get();
                onCallSucceeded();
                return result;
            } catch (ModelException e) {
                ProviderCallException pce = asProviderCall(e);
                onCallFailed(pce);

                if (pce.getProviderCode() == ProviderCallException.ProviderErrorCode.AUTH_ERROR
                        && credentialRotation != null && attempt < maxRetries) {
                    String rotated = credentialRotation.nextCredential();
                    if (rotated != null) {
                        // Rotation took effect on the delegate via the
                        // callback; retry IMMEDIATELY — a fresh key deserves
                        // a fresh attempt, not a backoff nap.
                        credentialRotation.apply(rotated);
                        last = pce;
                        continue;
                    }
                }

                if (!isRetryable(pce) || attempt == maxRetries || breakerOpen()) {
                    throw pce;
                }
                last = pce;
                Duration wait = waitDuration(pce, attempt);
                if (!wait.isZero()) {
                    sleep(wait);
                }
            }
        }
        throw last != null ? last
                : new ProviderCallException(ProviderCallException.ProviderErrorCode.UNKNOWN,
                        "unreachable resilience state");
    }

    private void checkBreakerBeforeCall(String op) {
        BreakerState state = breakerState.get();
        if (state == BreakerState.OPEN) {
            long openedAt = openedAtMillis;
            if (openedAt > 0 && System.currentTimeMillis() - openedAt >= openDuration.toMillis()) {
                // eligible to probe
                if (breakerState.compareAndSet(BreakerState.OPEN, BreakerState.HALF_OPEN)) {
                    return; // this call IS the probe
                }
                state = breakerState.get();
            }
            if (state == BreakerState.OPEN) {
                throw new ProviderCallException(
                        ProviderCallException.ProviderErrorCode.MODEL_ERROR,
                        "circuit open for '" + op + "' after " + consecutiveFailures.get()
                                + " consecutive failures; fail-fast until "
                                + Instant.ofEpochMilli(openedAt + openDuration.toMillis()));
            }
        }
    }

    private void onCallSucceeded() {
        consecutiveFailures.set(0);
        breakerState.set(BreakerState.CLOSED);
        openedAtMillis = -1;
    }

    private void onCallFailed(ProviderCallException e) {
        // Caller-side categories (bad request payload, caller-initiated
        // cancel) are NOT provider sickness: they must not open the breaker
        // and block healthy traffic for everyone else.
        if (e.getProviderCode() == ProviderCallException.ProviderErrorCode.INVALID_REQUEST
                || e.getProviderCode() == ProviderCallException.ProviderErrorCode.CANCELED) {
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && breakerState.get() != BreakerState.OPEN) {
            breakerState.set(BreakerState.OPEN);
            openedAtMillis = System.currentTimeMillis();
        }
    }

    private boolean breakerOpen() {
        return breakerState.get() == BreakerState.OPEN;
    }

    private static boolean isRetryable(ProviderCallException e) {
        return e.isRetryable();
    }

    private Duration waitDuration(ProviderCallException e, int attempt) {
        Duration hinted = e.getRetryAfter();
        if (hinted != null && !hinted.isNegative()) {
            if (retryAfterCap != null && hinted.compareTo(retryAfterCap) > 0) {
                return retryAfterCap;
            }
            return hinted;
        }
        long millis = (long) (initialBackoff.toMillis() * Math.pow(2, attempt));
        return Duration.ofMillis(Math.min(millis, 60_000));
    }

    private static ProviderCallException asProviderCall(ModelException e) {
        if (e instanceof ProviderCallException pce) {
            return pce;
        }
        // Legacy exception from a pre-Stage-6 client: upgrade in place
        return new ProviderCallException(
                ProviderCallException.ProviderErrorCode.fromLegacy(e.getCode()),
                e.getMessage(), e, null, 0, null);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ProviderCallException(ProviderCallException.ProviderErrorCode.CANCELED,
                    "Interrupted during resilience backoff", ie);
        }
    }

    public BreakerState getBreakerState() {
        return breakerState.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Supplies replacement credentials on AUTH_ERROR. Implementations own
     * the storage/refresh policy (vault, env, pool); the decorator only
     * decides WHEN to rotate.
     */
    public interface CredentialRotation {

        /**
         * @return the next credential, or null when the pool is exhausted
         *         (further rotation is pointless; the error propagates)
         */
        String nextCredential();

        /**
         * Apply the rotated credential to the underlying delegate (e.g.
         * swap the API key on the wrapped client). Called once per rotation.
         */
        void apply(String credential);
    }
}

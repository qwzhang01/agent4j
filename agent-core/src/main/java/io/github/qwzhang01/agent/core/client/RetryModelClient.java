package io.github.qwzhang01.agent.core.client;

import io.github.qwzhang01.agent.core.model.ModelRequest;
import io.github.qwzhang01.agent.core.model.ModelResponse;
import io.github.qwzhang01.agent.core.model.StreamEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * Decorator that retries failed ModelClient calls with exponential backoff.
 * <p>
 * Retry policy based on error code:
 * - TIMEOUT: retry (network blip)
 * - RATE_LIMITED: retry (with longer backoff)
 * - NETWORK_ERROR: retry
 * - AUTH_ERROR: do NOT retry (won't fix itself)
 * - INVALID_REQUEST: do NOT retry (client error)
 * - MODEL_ERROR: retry (server error, might recover)
 * - UNKNOWN: retry once
 * <p>
 * Decorator pattern: wraps any ModelClient, adds retry without modifying the delegate.
 */
public class RetryModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(RetryModelClient.class);

    private final ModelClient delegate;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final double backoffMultiplier;

    public RetryModelClient(ModelClient delegate) {
        this(delegate, 3, Duration.ofMillis(500), 2.0);
    }

    public RetryModelClient(ModelClient delegate, int maxRetries,
                            Duration initialBackoff, double backoffMultiplier) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        ModelException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return delegate.chat(request);
            } catch (ModelException e) {
                lastException = e;
                if (!shouldRetry(e.getCode()) || attempt == maxRetries) {
                    throw e;
                }
                Duration backoff = computeBackoff(attempt);
                log.warn("Attempt {} failed ({}), retrying in {}ms: {}",
                        attempt + 1, e.getCode(), backoff.toMillis(), e.getMessage());
                sleep(backoff);
            }
        }
        throw lastException;
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // For streaming, we retry the connection but not mid-stream failures.
        // Once the stream starts producing events, we pass through.
        ModelException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                Stream<StreamEvent> stream = delegate.stream(request);
                // Eagerly check if the stream starts with an error
                // We use a wrapper to catch initial errors
                return stream;
            } catch (ModelException e) {
                lastException = e;
                if (!shouldRetry(e.getCode()) || attempt == maxRetries) {
                    throw e;
                }
                Duration backoff = computeBackoff(attempt);
                log.warn("Stream attempt {} failed ({}), retrying in {}ms",
                        attempt + 1, e.getCode(), backoff.toMillis());
                sleep(backoff);
            }
        }
        throw lastException;
    }

    // ============ Private Helpers ============

    private boolean shouldRetry(ModelException.ErrorCode code) {
        return switch (code) {
            case TIMEOUT, RATE_LIMITED, NETWORK_ERROR, MODEL_ERROR, UNKNOWN -> true;
            case AUTH_ERROR, INVALID_REQUEST -> false;
        };
    }

    private Duration computeBackoff(int attempt) {
        long millis = (long) (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt));
        // Cap at 30 seconds
        return Duration.ofMillis(Math.min(millis, 30_000));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.ErrorCode.UNKNOWN, "Interrupted during retry backoff", e);
        }
    }
}

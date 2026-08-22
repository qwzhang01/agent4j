package io.github.qwzhang01.agent.core.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Shared exponential-backoff retry for model / generation clients.
 * <p>
 * Same error-code policy as {@link RetryModelClient}: timeout, rate-limit,
 * network and model errors retry; auth and invalid-request do not.
 */
public final class ClientRetry {

    private static final Logger log = LoggerFactory.getLogger(ClientRetry.class);

    private ClientRetry() {
    }

    public static <T> T call(String label, Supplier<T> action,
                             int maxRetries, Duration initialBackoff, double backoffMultiplier) {
        ModelException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (ModelException e) {
                lastException = e;
                if (!shouldRetry(e.getCode()) || attempt == maxRetries) {
                    throw e;
                }
                Duration backoff = computeBackoff(attempt, initialBackoff, backoffMultiplier);
                log.warn("{} attempt {} failed ({}), retrying in {}ms: {}",
                        label, attempt + 1, e.getCode(), backoff.toMillis(), e.getMessage());
                sleep(backoff);
            }
        }
        throw lastException;
    }

    public static boolean shouldRetry(ModelException.ErrorCode code) {
        return switch (code) {
            case TIMEOUT, RATE_LIMITED, NETWORK_ERROR, MODEL_ERROR, UNKNOWN -> true;
            case AUTH_ERROR, INVALID_REQUEST -> false;
        };
    }

    static Duration computeBackoff(int attempt, Duration initialBackoff, double backoffMultiplier) {
        long millis = (long) (initialBackoff.toMillis() * Math.pow(backoffMultiplier, attempt));
        return Duration.ofMillis(Math.min(millis, 30_000));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelException(ModelException.ErrorCode.UNKNOWN,
                    "Interrupted during retry backoff", e);
        }
    }
}

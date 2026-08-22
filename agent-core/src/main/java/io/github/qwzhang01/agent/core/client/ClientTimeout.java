package io.github.qwzhang01.agent.core.client;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Shared call-level timeout for model / generation clients.
 */
public final class ClientTimeout {

    private ClientTimeout() {
    }

    public static <T> T call(String label, Supplier<T> action, Duration timeout, ExecutorService executor) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(action, executor);
        try {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                future.cancel(true);
                throw new ModelException(ModelException.ErrorCode.TIMEOUT,
                        label + " timed out after " + timeout.toMillis() + "ms");
            }
            Throwable cause = e.getCause();
            if (cause instanceof ModelException me) {
                throw me;
            }
            throw new ModelException(ModelException.ErrorCode.UNKNOWN,
                    label + " failed: " + (cause != null ? cause.getMessage() : e.getMessage()),
                    cause != null ? cause : e);
        }
    }
}

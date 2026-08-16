package com.seven.agent.core.client;

import com.seven.agent.core.model.ModelRequest;
import com.seven.agent.core.model.ModelResponse;
import com.seven.agent.core.model.StreamEvent;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.stream.Stream;

/**
 * Decorator that enforces a timeout on ModelClient calls.
 * <p>
 * Uses CompletableFuture.orTimeout for synchronous calls.
 * For streaming, sets a connection-level timeout.
 * <p>
 * On timeout, throws ModelException with ErrorCode.TIMEOUT.
 */
public class TimeoutModelClient implements ModelClient {

    private final ModelClient delegate;
    private final Duration timeout;
    private final ExecutorService executor;

    /**
     * @param delegate underlying ModelClient
     * @param timeout  max duration for a single call
     */
    public TimeoutModelClient(ModelClient delegate, Duration timeout) {
        this(delegate, timeout, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "model-client-timeout");
            t.setDaemon(true);
            return t;
        }));
    }

    public TimeoutModelClient(ModelClient delegate, Duration timeout, ExecutorService executor) {
        this.delegate = delegate;
        this.timeout = timeout;
        this.executor = executor;
    }

    @Override
    public ModelResponse chat(ModelRequest request) {
        CompletableFuture<ModelResponse> future = CompletableFuture.supplyAsync(
                () -> delegate.chat(request), executor);

        try {
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                future.cancel(true);
                throw new ModelException(ModelException.ErrorCode.TIMEOUT,
                        "Model call timed out after " + timeout.toMillis() + "ms");
            }
            // Unwrap and rethrow
            Throwable cause = e.getCause();
            if (cause instanceof ModelException me) {
                throw me;
            }
            throw new ModelException(ModelException.ErrorCode.UNKNOWN,
                    "Model call failed: " + cause.getMessage(), cause);
        }
    }

    @Override
    public Stream<StreamEvent> stream(ModelRequest request) {
        // For streaming, we start the connection with a timeout,
        // but the stream itself is not time-bounded (it may be long).
        CompletableFuture<Stream<StreamEvent>> future = CompletableFuture.supplyAsync(
                () -> delegate.stream(request), executor);

        try {
            // Use a longer timeout for stream initialization
            return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                throw new ModelException(ModelException.ErrorCode.TIMEOUT,
                        "Stream connection timed out after " + timeout.toMillis() + "ms");
            }
            Throwable cause = e.getCause();
            if (cause instanceof ModelException me) {
                throw me;
            }
            throw new ModelException(ModelException.ErrorCode.UNKNOWN,
                    "Stream initialization failed: " + cause.getMessage(), cause);
        }
    }

    /**
     * Shut down the internal executor.
     */
    public void shutdown() {
        executor.shutdown();
    }
}

package io.github.qwzhang01.agent.core.client;

import java.time.Duration;

/**
 * Retry decorator for {@link VideoGenerationClient} HTTP calls (submit / status / download).
 * Does not wrap {@link #awaitCompletion} — that method already polls with its own timeout.
 */
public class RetryVideoGenerationClient implements VideoGenerationClient {

    private final VideoGenerationClient delegate;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final double backoffMultiplier;

    public RetryVideoGenerationClient(VideoGenerationClient delegate) {
        this(delegate, 3, Duration.ofMillis(500), 2.0);
    }

    public RetryVideoGenerationClient(VideoGenerationClient delegate, int maxRetries,
                                      Duration initialBackoff, double backoffMultiplier) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public VideoTask submit(VideoGenRequest request) {
        return ClientRetry.call("video.submit", () -> delegate.submit(request),
                maxRetries, initialBackoff, backoffMultiplier);
    }

    @Override
    public VideoTask status(String taskId) {
        return ClientRetry.call("video.status", () -> delegate.status(taskId),
                maxRetries, initialBackoff, backoffMultiplier);
    }

    @Override
    public byte[] downloadContent(String taskId) {
        return ClientRetry.call("video.download", () -> delegate.downloadContent(taskId),
                maxRetries, initialBackoff, backoffMultiplier);
    }
}

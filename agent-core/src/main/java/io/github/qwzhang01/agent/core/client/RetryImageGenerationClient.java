package io.github.qwzhang01.agent.core.client;

import java.time.Duration;

/**
 * Retry decorator for {@link ImageGenerationClient}, same policy as {@link RetryModelClient}.
 */
public class RetryImageGenerationClient implements ImageGenerationClient {

    private final ImageGenerationClient delegate;
    private final int maxRetries;
    private final Duration initialBackoff;
    private final double backoffMultiplier;

    public RetryImageGenerationClient(ImageGenerationClient delegate) {
        this(delegate, 3, Duration.ofMillis(500), 2.0);
    }

    public RetryImageGenerationClient(ImageGenerationClient delegate, int maxRetries,
                                      Duration initialBackoff, double backoffMultiplier) {
        this.delegate = delegate;
        this.maxRetries = maxRetries;
        this.initialBackoff = initialBackoff;
        this.backoffMultiplier = backoffMultiplier;
    }

    @Override
    public ImageResult generate(ImageGenRequest request) {
        return ClientRetry.call("image.generate", () -> delegate.generate(request),
                maxRetries, initialBackoff, backoffMultiplier);
    }
}
